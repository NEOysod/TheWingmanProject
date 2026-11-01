"""Validate staged standalone mods together. Usage: python -I test/check_mod_split.py build"""
import csv
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        raise AssertionError(message)


def load_mod(root):
    info = json.loads((root / "mod_info.json").read_text(encoding="utf-8"))
    settings_path = root / "data/config/settings.json"
    settings = json.loads(settings_path.read_text(encoding="utf-8")) if settings_path.exists() else {}
    csv_path = root / "data/characters/skills/skill_data.csv"
    rows = {}
    if csv_path.exists():
        with csv_path.open(encoding="utf-8", newline="") as stream:
            for row in csv.DictReader(stream):
                check(None not in row and None not in row.values(), f"CSV column shape: {root.name}")
                check(row["id"] not in rows, f"Unique skill ID: {row['id']}")
                rows[row["id"]] = row
    specs = {}
    skills_dir = root / "data/characters/skills"
    for path in skills_dir.glob("*.skill"):
        spec = json.loads(path.read_text(encoding="utf-8"))
        check(spec["id"] == path.stem, f"Skill filename matches ID: {path}")
        specs[spec["id"]] = spec
    check(set(specs) == set(rows), f"No stale/missing skill definitions: {root.name}")
    classes = set()
    for jar_path in info["jars"]:
        with zipfile.ZipFile(root / jar_path) as jar:
            check(jar.testzip() is None, f"Readable JAR: {jar_path}")
            classes.update(name for name in jar.namelist() if name.endswith(".class"))
    for skill_id, spec in specs.items():
        icon = rows[skill_id]["icon"]
        if icon != "graphics/icons/skills/officer_training.png":
            check((root / icon).is_file(), f"Bundled icon: {skill_id}")
        for group in spec["effectGroups"]:
            for effect in group["effects"]:
                check(effect["script"].replace(".", "/") + ".class" in classes, f"Effect class packaged: {skill_id}")
    return info, settings.get("plugins", {}), rows, specs, classes


def verify_archive(build, name, archive_name):
    root = build / name
    archive = build / archive_name
    with zipfile.ZipFile(archive) as zipped:
        check(zipped.testzip() is None, f"Readable ZIP: {archive.name}")
        actual = {entry.filename.replace("\\", "/"): entry for entry in zipped.infolist() if not entry.is_dir()}
        expected = {name + "/" + path.relative_to(root).as_posix(): path for path in root.rglob("*") if path.is_file()}
        check(set(actual) == set(expected), f"ZIP exactly matches complete staged mod: {name}")
        for key, path in expected.items():
            check(hashlib.sha256(zipped.read(actual[key])).digest() == hashlib.sha256(path.read_bytes()).digest(), f"ZIP payload: {key}")


def main(build):
    player = load_mod(build / "VoidborneAscendancy")
    npc = load_mod(build / "IroncladCadre")
    wingman = load_mod(build / "Wingman")
    check(player[0]["id"] == "voidborne_ascendancy" and npc[0]["id"] == "ironclad_cadre", "Independent mod IDs")
    check({dep["id"] for dep in player[0]["dependencies"]} == {"patchlib"}, "Player requires only PatchLib")
    check({dep["id"] for dep in npc[0]["dependencies"]} == {"patchlib"}, "NPC mod requires only PatchLib")
    check(not player[1], "Player mod registers no officer/multiplayer plugins")
    check(npc[1] == {"officerLevelUp": "wingman.OfficerPotentialLevelupPlugin"}, "NPC mod registers only officer picker")
    check("officerLevelUp" not in wingman[1], "Wingman no longer replaces officer picker")
    check("wingman_officer_binding" in wingman[1], "Optional multiplayer binding retained")
    check(len(player[2]) == 18 and len(npc[2]) == 18 and set(wingman[2]) == {"wingman_officer_potential"}, "Correct skill ownership")
    check(set(player[2]).isdisjoint(npc[2]), "No duplicate skill IDs when both enabled")
    for left, right, label in [(player, npc, "player/NPC"), (player, wingman, "player/Wingman"), (npc, wingman, "NPC/Wingman")]:
        check(left[4].isdisjoint(right[4]), f"No duplicate Java classes: {label}")
    check(all(name.startswith("voidborne/") for name in player[4]), "Player has no Wingman classes")
    check(all(name.startswith("wingman/OfficerCombatSkills") or name == "wingman/OfficerPotentialLevelupPlugin.class" or name.startswith(("wingman/OfficerLevelupMenu", "wingman/patch/OfficerLevelupMenuPatch")) for name in npc[4]), "NPC JAR contains only independent combat skills, picker and level-up menu")
    check(not any("SkillScreenScrollPatch" in name or name.startswith("wingman/OfficerCombatSkills") or name == "wingman/OfficerPotentialLevelupPlugin.class" for name in wingman[4]), "Extracted combat skills and picker absent from Wingman")
    check({"wingman/OfficerPotential.class", "wingman/OfficerPotential$Description.class", "wingman/patch/Patches$OfficerPotentialCap.class"}.issubset(wingman[4]), "Multiplayer trait and cap patch belong to Wingman")
    check("voidborne/patch/SkillScreenScrollPatch.class" in player[4], "Scrollbar belongs to player mod")
    for skill_id, row in npc[2].items():
        check(row["tags"] == "npc_only" and row["combat officer"] == "TRUE", f"NPC isolation: {skill_id}")
    for skill_id, row in player[2].items():
        check(row["tags"] == "player_only" and not row["combat officer"] and not row["admiral"], f"Player isolation: {skill_id}")
        legacy = "wingman_" + skill_id.removeprefix("voidborne_")
        player_spec = player[3][skill_id]
        npc_spec = npc[3][legacy]
        normalized = json.loads(json.dumps(player_spec).replace("voidborne_", "wingman_").replace("voidborne.", "wingman."))
        check(normalized == npc_spec, f"Ranks, scopes and effects unchanged: {skill_id}")
        check((build / "VoidborneAscendancy" / row["icon"]).read_bytes() == (build / "IroncladCadre" / npc[2][legacy]["icon"]).read_bytes(), f"Same icon under independent path: {skill_id}")
    bodies = []
    for mod, package in [("VoidborneAscendancy", "voidborne"), ("IroncladCadre", "wingman")]:
        source = (build / mod / "src" / package / "OfficerCombatSkills.java").read_text(encoding="utf-8")
        bodies.append(source[source.index("    public abstract static class Effect"):])
    check(bodies[0] == bodies[1], "Both effect implementations exactly match tested NPC behavior")
    verify_archive(build, "VoidborneAscendancy", "VoidborneAscendancy-1.0.0.zip")
    verify_archive(build, "IroncladCadre", "IroncladCadre-1.0.0.zip")
    verify_archive(build, "Wingman", "Wingman-multiplayer-only.zip")
    print(f"Mod split: {checks} checks passed (ownership, independent packages, coexistence, effects and ZIP payloads).")


if __name__ == "__main__":
    main(Path(sys.argv[1]).resolve())
