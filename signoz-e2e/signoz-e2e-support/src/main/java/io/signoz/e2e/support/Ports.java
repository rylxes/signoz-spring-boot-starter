package io.signoz.e2e.support;

import java.io.IOException;
import java.net.ServerSocket;

/** Free local ports. */
public final class Ports {

    private Ports() {
    }

    public static int free() {
        return free(1)[0];
    }

    /**
     * {@code n} distinct free ports. The sockets are held open together while the ports are
     * chosen: taken one at a time, the OS can hand out the same port twice.
     */
    public static int[] free(int n) {
        ServerSocket[] sockets = new ServerSocket[n];
        try {
            int[] ports = new int[n];
            for (int i = 0; i < n; i++) {
                sockets[i] = new ServerSocket(0);
                sockets[i].setReuseAddress(true);
                ports[i] = sockets[i].getLocalPort();
            }
            return ports;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } finally {
            for (ServerSocket s : sockets) {
                if (s != null) {
                    try {
                        s.close();
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
            }
        }
    }
}
