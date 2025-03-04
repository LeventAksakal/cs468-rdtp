package model;

import java.net.InetAddress;
import java.net.NetworkInterface;

public class InetAddressInterface {
	private NetworkInterface net_if = null;
	private InetAddress addr = null;

	public InetAddressInterface(NetworkInterface net_if, InetAddress addr) {
		this.net_if = net_if;
		this.addr = addr;
	}

	public NetworkInterface getNetqorkInterface() {
		return net_if;
	}

	public InetAddress getInetAddress() {
		return addr;
	}

	@Override
	public String toString() {
		return net_if.getName() + "#" + net_if.getDisplayName() + "#" + addr.getHostAddress();
	}

}
