package wingman.net;

/** A deep copy of a snapshot, for the tests in this package (the same as NetTest.copyOf). */
final class NetTestCopy {
    static Snapshot copyOf(Snapshot s) {
        Snapshot c = new Snapshot(); c.tick = s.tick; c.hostTime = s.hostTime;
        for (Snapshot.ShipState x : s.ships) {
            Snapshot.ShipState y = new Snapshot.ShipState();
            y.netId = x.netId; y.x = x.x; y.y = x.y; y.vx = x.vx; y.vy = x.vy; y.facing = x.facing; y.angVel = x.angVel; y.hull = x.hull; y.cr = x.cr; y.flux = x.flux; y.hardFlux = x.hardFlux;
            y.flags = x.flags; y.shieldFacing = x.shieldFacing; y.firing = x.firing; y.system = x.system; y.cloak = x.cloak; y.systemLevel = x.systemLevel; y.cloakLevel = x.cloakLevel;
            y.weaponAngles = x.weaponAngles == null ? null : x.weaponAngles.clone(); y.shotTotals = x.shotTotals == null ? null : x.shotTotals.clone();
            y.fluxStep = x.fluxStep; y.exact = x.exact;
            c.ships.add(y);
        }
        for (Snapshot.MissileState x : s.missiles) {
            Snapshot.MissileState y = new Snapshot.MissileState();
            y.id = x.id; y.x = x.x; y.y = x.y; y.vx = x.vx; y.vy = x.vy; y.facing = x.facing; y.angVel = x.angVel; y.flags = x.flags; y.flightTime = x.flightTime;
            c.missiles.add(y);
        }
        return c;
    }
}
