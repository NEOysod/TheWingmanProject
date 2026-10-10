import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Build tool, not part of the mod. Renames one name inside a compiled class file.
 * Usage: java PatchNew path/to/File.class oldName newName
 *
 * Needed because the game's list of missions comes from a method literally called "new", which Java source
 * cannot spell. TitleLauncher is compiled against a stand-in called coopNew(), and this renames it.
 */
public class PatchNew {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Paths.get(a[0]));
        DataInputStream in = new DataInputStream(new java.io.ByteArrayInputStream(d));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(in.readInt()); out.writeShort(in.readUnsignedShort()); out.writeShort(in.readUnsignedShort());
        int n = in.readUnsignedShort(); out.writeShort(n);
        int hits = 0;
        for (int i = 1; i < n; i++) {
            int tag = in.readUnsignedByte(); out.writeByte(tag);
            if (tag == 1) {
                String s = in.readUTF();
                if (s.equals(a[1])) { s = a[2]; hits++; }
                out.writeUTF(s);
                continue;
            }
            int size = tag == 7 || tag == 8 || tag == 16 || tag == 19 || tag == 20 ? 2 : tag == 15 ? 3 : tag == 5 || tag == 6 ? 8 : 4;
            byte[] b = new byte[size]; in.readFully(b); out.write(b);
            if (tag == 5 || tag == 6) i++;
        }
        byte[] rest = new byte[in.available()]; in.readFully(rest); out.write(rest);
        if (hits != 1) throw new IllegalStateException("expected exactly one '" + a[1] + "', found " + hits);
        Files.write(Paths.get(a[0]), bytes.toByteArray());
        System.out.println("Renamed " + a[1] + " -> " + a[2] + " in " + a[0]);
    }
}
