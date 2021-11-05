package com.pk.server.models;

import java.net.InetAddress;
import lombok.Value;

/** Wrapper class containing information about player. */
@Value
public class Player {
  InetAddress ip;
  String nick;
  String profileImg;
}
