package com.pk;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.security.InvalidAlgorithmParameterException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;

import com.pk.models.Config;
import com.pk.models.Player;

import org.apache.commons.codec.binary.Base64;
import org.apache.commons.collections4.BidiMap;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class Server implements Callable<Integer> {
  private Selector selector;
  private ServerSocketChannel serverSocketChannel;
  // Nickname <-> SocketChannel
  private BidiMap<String, SocketChannel> connectedPlayersMap;
  // invite code <-> SocketChannel
  private BidiMap<String, SocketChannel> inviteCodeMap;
  // All active players
  private Set<Player> hashPlayers;

  public Server(
      String ip,
      Integer port,
      BidiMap<String, SocketChannel> connectedPlayersMap,
      BidiMap<String, SocketChannel> inviteCodeMap,
      Set<Player> hashPlayers)
      throws IOException {
    selector = Selector.open();
    serverSocketChannel = ServerSocketChannel.open();
    serverSocketChannel.configureBlocking(false);
    serverSocketChannel.bind(new InetSocketAddress(ip, port));
    serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
    this.connectedPlayersMap = connectedPlayersMap;
    this.inviteCodeMap = inviteCodeMap;
    this.hashPlayers = hashPlayers;
  }

  public void cleanup() throws IOException {
    serverSocketChannel.close();
    selector.close();
  }

  @Override
  public Integer call() throws Exception {
    SelectionKey key = null;
    while (true) {
      if (Thread.currentThread().isInterrupted()) {
        return 0;
      }
      if (selector.select() <= 0) continue;
      Set<SelectionKey> set = selector.selectedKeys();
      Iterator<SelectionKey> iterator = set.iterator();
      while (iterator.hasNext()) {
        key = iterator.next();
        iterator.remove();
        if (key.isAcceptable()) {
          try {
            handleAccept();
          } catch (IOException e) {
            log.error("Accept error, terminating");
            key.cancel();
            continue;
          }
        }
        if (key.isReadable()) {
          try {
            handleReadMsg(key);
          } catch (IOException e) {
            log.error("Connection error, terminating");
            key.cancel();
          }
        }
      }
    }
  }

  private void handleAccept() throws IOException {
    SocketChannel sc = serverSocketChannel.accept();
    sc.configureBlocking(false);
    sc.register(selector, SelectionKey.OP_READ);
    sc.write(ByteBuffer.wrap("checkers:Hello".getBytes()));
    log.info("Connection Accepted: " + sc.getLocalAddress());
  }

  private void handleClosedConnection(SelectionKey key, SocketChannel sc) {
    String nickToDelete = connectedPlayersMap.getKey(sc);
    connectedPlayersMap.remove(nickToDelete, sc);
    for (Iterator<Player> it = hashPlayers.iterator(); it.hasNext(); ) {
      Player player = it.next();
      if (player.getNickname().equals(nickToDelete)) {
        it.remove();
      }
    }
    String uuid = inviteCodeMap.getKey(sc);
    inviteCodeMap.remove(uuid, sc);
    key.cancel();
  }

  private void handleReadMsg(SelectionKey key) throws IOException {
    SocketChannel sc = (SocketChannel) key.channel();
    ByteBuffer bb = ByteBuffer.allocate(1024);
    int len = sc.read(bb);
    if (len <= 0) {
      sc.close();
      log.info("Connection closed");
      handleClosedConnection(key, sc);
      return;
    }
    String rawMsg = new String(bb.array(), 0, len).strip();
    if (rawMsg.charAt(rawMsg.length() - 1) == '!') {
      rawMsg = rawMsg.substring(0, rawMsg.length() - 1);
    }
    log.info("Message received: <{}>, len: {}", rawMsg, rawMsg.length());
    String[] messages = rawMsg.split("!");
    log.info("Split messages: <{}>", Arrays.toString(messages));
    for (String msg : messages) {
      handleSingleMessage(key, sc, msg);
    }
  }

  private void handleSingleMessage(SelectionKey key, SocketChannel sc, String msg) {
    if (msg.startsWith("checkers:config ")) {
      try {
        handleConfig(sc, msg);
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else if (msg.equals("checkers:getPlayers")) {
      try {
        sc.write(ByteBuffer.wrap(transformPlayersListIntoBytes()));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else if (msg.startsWith("checkers:inviteAsk ")) {
      try {
        handleInviteAsk(sc, msg.substring(19));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else if (msg.startsWith("checkers:inviteRejected ")) {
      try {
        handleInviteRejected(msg.substring(24));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else if (msg.startsWith("checkers:inviteOk ")) {
      try {
        handleInviteOk(sc, msg.substring(18));
      } catch (IOException e) {
        e.printStackTrace();
      }
    } else {
      log.warn("Got invalid message: {}", msg);
    }
  }

  private void handleInviteRejected(String msg) throws IOException {
    SocketChannel channel = inviteCodeMap.get(msg);
    channel.write(ByteBuffer.wrap("checkers:inviteRejected".getBytes()));
  }

  private void handleInviteOk(SocketChannel sc, String msg) throws IOException {
    SocketChannel channel = inviteCodeMap.get(msg);
    channel.write(ByteBuffer.wrap(("checkers:inviteOk " + msg).getBytes()));
    try {
      sc.keyFor(selector).cancel();
      sc.configureBlocking(true);
      channel.keyFor(selector).cancel();
      channel.configureBlocking(true);
    } catch (IOException e) {
      log.error("configureBlocking throw IOException, socketChannel is not canceled (?)", e);
      return;
    }
    Thread th =
        new Thread(
            new SessionHandler(
                sc.socket(),
                channel.socket(),
                connectedPlayersMap,
                inviteCodeMap,
                hashPlayers));
    th.start();
  }

  private void handleInviteAsk(SocketChannel sc, String msg) throws IOException {
    // FIXME magic number
    if (msg.length() != 12) {
      log.warn(String.format("Invalid msg length, got %s should be %s", msg.length(), 12));
      return;
    }
    if (!inviteCodeMap.containsKey(msg)) {
      log.warn("Message code not found");
      sc.write(ByteBuffer.wrap("checkers:inviteErr".getBytes()));
      return;
    }
    Player play = null;
    SocketChannel dest = inviteCodeMap.get(msg);
    String nick = connectedPlayersMap.getKey(sc);
    for (Player player : hashPlayers) {
      if (player.getNickname().equals(nick)) {
        play = player;
        break;
      }
    }
    if (play == null) {
      log.error("Player is null, not possible");
      return;
    }
    String srcCode = inviteCodeMap.getKey(sc);
    dest.write(
        ByteBuffer.wrap(
            String.format(
                    "checkers:inviteAsk %s %s %s",
                    play.getNickname(), play.getProfileImg(), srcCode)
                .getBytes()));
  }

  private void handleConfig(SocketChannel sc, String msg) throws IOException {
    if (connectedPlayersMap.containsValue(sc)) {
      sc.write(ByteBuffer.wrap("checkers:confUserTaken".getBytes()));
    }
    Optional<Config> conf = parseConfigMessage(msg.substring(16));
    if (conf.isEmpty()) {
      log.error("Got invalid config message, ignoring");
      return;
    }
    Config config = conf.get();
    if (!Base64.isBase64(config.getNickname())) {
      sc.write(ByteBuffer.wrap("checkers:confBadNick".getBytes()));
    } else if (!Base64.isBase64(config.getProfileImg())) {
      sc.write(ByteBuffer.wrap("checkers:confBadImg".getBytes()));
    }
    if (connectedPlayersMap.containsKey(conf.get().getNickname())) {
      log.warn("User already present on server: " + conf.get().getNickname());
      sc.write(ByteBuffer.wrap("checkers:error user already present".getBytes()));
      return;
    }
    connectedPlayersMap.put(conf.get().getNickname(), sc);
    hashPlayers.add(new Player(conf.get().getNickname(), conf.get().getProfileImg()));
    ByteBuffer src = ByteBuffer.wrap(("checkers:confOk " + generateInviteCode(sc)).getBytes());
    sc.write(src);
  }

  public String generateInviteCode(SocketChannel sc) {
    String uuid = null;
    for (; ; ) {
      uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
      if (!inviteCodeMap.containsKey(uuid)) {
        break;
      }
    }
    inviteCodeMap.put(uuid, sc);
    return uuid;
  }

  private byte[] transformPlayersListIntoBytes() {
    StringBuilder sb = new StringBuilder();
    sb.append("checkers:onlinePlayers ");
    for (Player player : hashPlayers) {
      sb.append(player.toSendable());
      sb.append(" ");
    }
    return sb.toString().strip().getBytes();
  }

  private Optional<Config> parseConfigMessage(String msg) {
    try {
      String[] parts = msg.split(" ");
      // FIXME magic number
      if (parts.length != 2) {
        throw new InvalidAlgorithmParameterException(
            String.format("Invalid len, should be: %s, is %s", 2, parts.length));
      }
      return Optional.of(new Config(parts[0], parts[1]));
    } catch (Exception e) {
      return Optional.empty();
    }
  }
}
