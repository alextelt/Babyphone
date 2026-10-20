package fr.alexis.babyphone;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Petit protocole binaire entre les deux téléphones (même réseau Wi-Fi).
 * Chaque trame = 1 octet de type + 4 octets de longueur + données.
 */
final class Protocol {
    static final int PORT = 47800;
    static final String NSD_TYPE = "_babyphone._tcp";
    static final String NSD_NAME = "Babyphone";
    static final int SAMPLE_RATE = 16000;
    /** 100 ms de son PCM 16 bits mono. */
    static final int FRAME_BYTES = 3200;

    // bébé -> parent
    static final byte HEARTBEAT = 'H';
    static final byte ALERT = 'A';
    static final byte AUDIO = 'P';
    // parent -> bébé
    static final byte LISTEN_ON = 'L';
    static final byte LISTEN_OFF = 'S';

    private Protocol() {}

    static final class Frame {
        final byte type;
        final byte[] data;

        Frame(byte type, byte[] data) {
            this.type = type;
            this.data = data == null ? new byte[0] : data;
        }

        String text() {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    static void write(DataOutputStream out, byte type, byte[] data) throws IOException {
        synchronized (out) {
            out.writeByte(type);
            int len = data == null ? 0 : data.length;
            out.writeInt(len);
            if (len > 0) {
                out.write(data);
            }
            out.flush();
        }
    }

    static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    static Frame read(DataInputStream in) throws IOException {
        byte type = in.readByte();
        int len = in.readInt();
        if (len < 0 || len > (1 << 20)) {
            throw new IOException("Trame invalide");
        }
        byte[] data = new byte[len];
        in.readFully(data);
        return new Frame(type, data);
    }
}
