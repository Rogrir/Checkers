package com.pk.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Base64;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Getter
@AllArgsConstructor
public class BasicProbeResponder implements ProbeResponder {
  private String nick;
  private String profileImg;

  public void setNick(String nick) {
    if (nick.length() == 0) {
      throw new IllegalArgumentException("Nick is empty");
    }
    this.nick = nick;
  }

  public void setProfileImg(String profileImg) {
    try {
      Base64.getDecoder().decode(profileImg);
    } catch (IllegalArgumentException iae) {
      throw new IllegalArgumentException("Nick is not base64 encoded");
    }
    if (nick.length() == 0) {
      throw new IllegalArgumentException("Nick is empty");
    }
    this.profileImg = profileImg;
  }

  @Override
  public void run() {
    for (;;) {
      try (DatagramSocket ds = createSocket(10000)) {
        String msg = recvMsg(ds);
        DatagramPacket dp = prepareResponse(msg);
        if (dp == null) {
          log.info("dp is null");
          continue;
        }
        log.info("DP: " + dp.toString());
        ds.send(dp);

      } catch (IOException e) {
        // TODO Auto-generated catch block
        e.printStackTrace();
      }
    }
  }

  private String recvMsg(DatagramSocket ds) throws IOException {
    byte[] buf = new byte[100];
    DatagramPacket dp = new DatagramPacket(buf, buf.length);
    ds.receive(dp);
    return new String(dp.getData(), 0, dp.getLength());
  }

  private DatagramPacket prepareResponse(String msg) throws UnknownHostException {
    if (!verifyProbe(msg)) {
      return null;
    }
    InetAddress addr = InetAddress.getByName("255.255.255.255");
    byte[] buf = String.format("checkers:probeResp %s %s", nick, profileImg).getBytes();
    return new DatagramPacket(buf, buf.length, addr, 10000);
  }

  private DatagramSocket createSocket(Integer port) throws SocketException {
    return new DatagramSocket(port);
  }

  private boolean verifyProbe(String msg) {
    try {
      if (msg.equals("checkers:probe")) {
        log.info("Got valid probe");
        return true;
      }
      log.info("Got invalid probe");
    } catch (IndexOutOfBoundsException e) {
      log.warn("Exception: ", e);
    }
    return false;
  }
}
