import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Build tool: in one compiled class, replace a piece of text in every name it refers to, for instance
 * "com/fs/starfarer/klass/" with "com/fs/starfarer/class/". The game has a package named "class", which Java
 * source can't spell; Wingman's code is compiled against a stand-in package and pointed at the real one here.
 * Usage: java PatchPackage File.class old new
 */
public class PatchPackage {
    public static void main(String[] a) throws Exception {
        byte[] d = Files.readAllBytes(Paths.get(a[0]));
        String from = a[1], to = a[2];
        if ((d[0] & 0xFF) != 0xCA || (d[1] & 0xFF) != 0xFE) throw new IllegalArgumentException("not a class file");
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bo);
        out.write(d, 0, 10);
        int n = ((d[8] & 0xFF) << 8) | (d[9] & 0xFF), p = 10, hits = 0;
        for (int i = 1; i < n; i++) {
            int tag = d[p] & 0xFF;
            if (tag == 1) {
                int len = ((d[p + 1] & 0xFF) << 8) | (d[p + 2] & 0xFF);
                String s = new String(d, p + 3, len, "UTF-8");
                if (s.contains(from)) { s = s.replace(from, to); hits++; }
                out.writeByte(1);
                out.writeUTF(s);
                p += 3 + len;
                continue;
            }
            int size;
            switch (tag) {
                case 7: case 8: case 16: case 19: case 20: size = 3; break;
                case 15: size = 4; break;
                case 5: case 6: size = 9; i++; break;
                default: size = 5;
            }
            out.write(d, p, size);
            p += size;
        }
        out.write(d, p, d.length - p);
        if (hits == 0) throw new IllegalStateException("no \"" + from + "\" in " + a[0]);
        Files.write(Paths.get(a[0]), bo.toByteArray());
        System.out.println("Patched " + hits + " names in " + a[0]);
    }
}
