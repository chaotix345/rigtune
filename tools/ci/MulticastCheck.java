import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

// docs/v0.5/SPEC.md AC1a.3: sends one datagram to 224.0.2.60:4445 and receives it on a MulticastSocket(4445) joined to
// that group, the way vanilla's LAN discovery pings and listens (LanServerPinger, LanServerDetection); then checks that an
// address off the machine (1.1.1.1:443) still can't be reached. build.yml runs it inside tools/ci/offline.sh, where
// loopback carries multicast and nothing else is reachable.
//   java tools/ci/MulticastCheck.java
public class MulticastCheck {
	@SuppressWarnings("deprecation") // joinGroup(InetAddress): vanilla's call
	public static void main(String[] args) {
		String sent = "[MOTD]RigTune multicast check " + System.nanoTime() + "[/MOTD][AD]25565[/AD]";
		try (MulticastSocket listener = new MulticastSocket(4445); DatagramSocket pinger = new DatagramSocket()) {
			InetAddress group = InetAddress.getByName("224.0.2.60");
			listener.setSoTimeout(5000);
			listener.joinGroup(group);
			byte[] bytes = sent.getBytes(StandardCharsets.UTF_8);
			pinger.send(new DatagramPacket(bytes, bytes.length, group, 4445));
			byte[] buffer = new byte[1024];
			while (true) {
				DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
				listener.receive(packet);
				if (sent.equals(new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8))) {
					System.out.println("Loopback multicast works: 224.0.2.60:4445 received from " + packet.getAddress().getHostAddress());
					break;
				}
			}
		} catch (IOException e) {
			System.out.println("::error::loopback multicast to 224.0.2.60:4445 failed: " + e);
			System.exit(1);
		}
		try (Socket outside = new Socket()) {
			outside.connect(new InetSocketAddress(InetAddress.getByAddress(new byte[] {1, 1, 1, 1}), 443), 3000);
			System.out.println("::error::1.1.1.1:443 is reachable: the namespace isn't offline");
			System.exit(1);
		} catch (IOException expected) {
			System.out.println("Nothing off the machine is reachable: 1.1.1.1:443 gave " + expected);
		}
	}
}
