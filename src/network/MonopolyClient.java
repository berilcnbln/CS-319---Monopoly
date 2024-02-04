package network;

import com.esotericsoftware.kryonet.Client;
import com.esotericsoftware.kryonet.Connection;
import com.esotericsoftware.kryonet.Listener;
import control.ActionLog;
import control.GameMode;
import control.MonopolyGame;
import control.PlayerController;
import control.action.*;
import entity.Player;
import entity.dice.DiceResult;
import entity.trade.OfferStatus;
import entity.trade.TradeOffer;
import gui.GameScreenController;
import gui.LobbyController;
import javafx.application.Platform;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

// create this when player joins to the lobby

// 1. client enters the ip address and clicks join game
// 2. MonopolyClient object gets created and logins into the specified IP Address
// 3. If login successful, MonopolyClient sends a player to the server and gets players from the server
// 4. UI switches to Lobby and LobbyController gets this players array
// 5. If a new player joins, server sends the array and MonopolyClient class updates the
//    LobbyController
public class MonopolyClient {

    // Connection members
    boolean isConnected = false;
    Client client;
    Connection connection;

    // Control members
    boolean gameStarted = false;
    GameScreenController gameScreenController;
    LobbyController lobbyController;

    // Lobby members
    String name;
    ArrayList<Player> players;
    boolean alliance ,speedDie, privateLobby;

    // Game members
    long seed;
    MonopolyGame monopolyGame;

    // Chat
    ChatMessage chatLog;

    public MonopolyClient(LobbyController lobbyController) {
        chatLog = new ChatMessage();
        name = "";
        client = new Client();
        new Thread(client).start(); // the thread keeps the client alive
        this.lobbyController = lobbyController;
        this.gameScreenController = null;

        MonopolyNetwork.register(client);

        client.addListener(new Listener() {
            @Override
            public void connected(Connection c) {
                isConnected = true;
                connection = c;

                connection.sendTCP("player name:" + name);
            }

            @Override
            public void idle(Connection connection) {

            }

            @Override
            public void disconnected(Connection c) {
                isConnected = false;
                connection = null;
            }

            @Override
            public void received(Connection connection, Object o) {
                if (gameStarted) {
                    if (o instanceof Action) {
                        Action action = (Action) o;
                        if (Objects.isNull(MonopolyGame.getActionLog())) {
                            MonopolyGame.actionLog = ActionLog.getInstance();
                        }
                        System.out.println("[CLIENT] Got action");
                        action.act();
                        Platform.runLater(() -> {
                            gameScreenController.updateBoardState();
                        });
                    }
                    else if (o instanceof ChatMessage) {
                        ChatMessage message = (ChatMessage) o;
                        Platform.runLater(() -> {
                            gameScreenController.updateChat(message);
                        });
                    }
                    else if (o instanceof ArrayList) {
                        players = (ArrayList<Player>) o;
                        monopolyGame.getPlayerController().setPlayers(players);
                        gameScreenController.updateBoardState();
                    }
                    else if (o instanceof DiceResult) {
                        DiceResult result = (DiceResult) o;
                        gameScreenController.setDiceLabel(result);
                    }
                    else if (o instanceof TradeOffer) {
                        TradeOffer tradeOffer = (TradeOffer) o;
                        if (tradeOffer.getStatus() == OfferStatus.AWAITING_RESPONSE ) {
                            Platform.runLater(() -> gameScreenController.showTradeDialog(tradeOffer));
                        }
                        else if (tradeOffer.getStatus() == OfferStatus.DECLINED ) {
                            // set status message to accepted if client is the request owner
                            if (tradeOffer.getSenderID() == getId() ) {
                                Platform.runLater(() -> {
                                    gameScreenController.getTradeController().getStatusLabel().setText("Status: Declined");
                                });

                            }

                        }
                        else if (tradeOffer.getStatus() == OfferStatus.ACCEPTED ) {
                            // set status message to accepted if client is the request owner
                            if (tradeOffer.getSenderID() == getId() ) {
                                Platform.runLater(() -> {
                                    gameScreenController.getTradeController().getStatusLabel().setText("Status: Accepted");
                                });
                            }

                            for (Integer i : tradeOffer.getPropertyOffered() ) {
                                new RemovePropertyAction(tradeOffer.getSenderID(), i).act();
                                new AddPropertyAction(tradeOffer.getReceiverID(), i).act();
                            }

                            for (Integer i : tradeOffer.getPropertyRequested() ) {
                                new RemovePropertyAction(tradeOffer.getReceiverID(), i).act();
                                new AddPropertyAction(tradeOffer.getSenderID(), i).act();
                            }

                            new RemoveMoneyAction(tradeOffer.getSenderID(), tradeOffer.getFeeOffered()).act();
                            new AddMoneyAction(tradeOffer.getReceiverID(), tradeOffer.getFeeOffered()).act();

                            new RemoveMoneyAction(tradeOffer.getReceiverID(), tradeOffer.getFeeRequested()).act();
                            new AddMoneyAction(tradeOffer.getSenderID(), tradeOffer.getFeeRequested()).act();

                            Platform.runLater(() -> gameScreenController.updateBoardState());
                        }

                    }
                    else if (o instanceof String) {
                        String s = (String) o;
                        if (s.equals("activate buttons")) {
                            Platform.runLater(() -> {
                                gameScreenController.activateButtons();
                            });
                            System.out.println("[SERVER] Activate buttons");
                        }
                        else if (s.equals("deactivate buttons")) {
                            gameScreenController.deactivateButtons();
                            System.out.println("[SERVER] Deactivate buttons");
                        }
                        else if (s.contains("active player:")) {
                            int charPos = s.indexOf(":");
                            int activePlayerIndex = Integer.parseInt(s.substring(charPos + 1));
                            gameScreenController.getGame().getPlayerController().setActivePlayerIndex(activePlayerIndex);
                            System.out.println("new active player: " + activePlayerIndex);
                            Platform.runLater(() -> gameScreenController.updateBoardState());
                        }
                        else if (s.contains("winner:")) {
                            int playerId = Integer.parseInt(s.substring(s.indexOf(":") + 1));
                            Platform.runLater(() -> gameScreenController.showWinnerDialog(PlayerController.getById(playerId)));
                        }
                        else {
                            System.out.println("[SERVER] " + s);
                        }
                    }
                }
                else {
                    if (o instanceof ArrayList) {
                        players = (ArrayList<Player>) o;
                        players.sort(Comparator.comparing(Player::getPlayerId));
                        lobbyController.updateLobbyState(MonopolyClient.this);
                    }
                    else if (o instanceof Player[]) {
                        Player[] playerArray = (Player[]) o;
                        players = new ArrayList<Player>(Arrays.asList(playerArray));
                        players.sort(Comparator.comparing(Player::getPlayerId));
                    }
                    else if (o instanceof Long) {
                        seed = (Long) o;
                    }
                    else if (o instanceof String) {
                        String message = (String) o;
                        if (message.equals("game started")) {
                                lobbyController.startGame();
                                gameStarted = true;
                        } // Activate and deactivate buttons don't need to be here
                        else if (message.equals("update lobby")) {
                            lobbyController.updateLobbyState(MonopolyClient.this);
                        }
                        else if (message.contains("active player:")) {
                            int id = Integer.parseInt(message.substring(message.indexOf(":") + 1));
                            gameScreenController.getGame().getPlayerController().setActivePlayerIndex(id);
                        }
                        else if (message.equals("server closed")) {
                            Platform.runLater(() -> lobbyController.leaveLobby());
                            client.stop();
                        }
                        else {
                            System.out.println("[SERVER] " + message);
                        }
                    }
                    else if (o instanceof boolean[]) {
                        boolean[] checkboxes = (boolean[]) o;

                        alliance = checkboxes[0];
                        speedDie = checkboxes[1];
                        privateLobby = checkboxes[2];
                    }
                }

            }
        });
    }

    public void connect(String ipAddress, String name) {
        this.name = name;
        System.out.println("[CLIENT] Connecting to the server...");
        MonopolyNetwork.ipAddress = ipAddress;
        //new Thread("Connect") {
        //    public void run () {
                try {
                    client.connect(5000, MonopolyNetwork.ipAddress, MonopolyNetwork.PORT);
                    // Server communication after connection can go here, or in Listener#connected().
                    System.out.println("[CLIENT] Connection successful to " + MonopolyNetwork.ipAddress + "!");
                } catch (IOException ex) {
                    ex.printStackTrace();
                    System.exit(1);
                }
        //    }
        //}.start();

    }

    public void sendStartGameCommand() {
        connection.sendTCP("start game");
    }

    public void sendAction(Action action) {
        connection.sendTCP(action);
    }

    public void sendLeftLobby(int playerId) {
        connection.sendTCP("leave lobby:" + playerId);
    }

    public void sendEndLobby() {
        // triggers lobbyController.leaveLobby() for all players
        connection.sendTCP("server closed");
    }

    public void disconnect() {
        client.stop();
    }

    public void sendChatMessage(ChatMessage chatMessage) {
        connection.sendTCP(chatMessage);
    }

    public void sendString(String s) { // can be used for commands
        connection.sendTCP(s);
    }

    public Client getClient() {
        return client;
    }

    public void setClient(Client client) {
        this.client = client;
    }

    public Connection getConnection() {
        return connection;
    }

    public void setConnection(Connection connection) {
        this.connection = connection;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public void setConnected(boolean connected) {
        isConnected = connected;
    }

    public ArrayList<Player> getPlayers() {
        return players;
    }

    public int getId() {
        for (Player player : players) {
            if (player.getName().equals(name)) {
                return player.getPlayerId();
            }
        }
        return -1;
    }

    public Player getById(int id) {
        for (Player player : players) {
            if (player.getPlayerId() == id)
                return player;
        }
        return null;
    }

    public ChatMessage getChatLog() {
        return chatLog;
    }

    public void setChatLog(ChatMessage chatLog) {
        this.chatLog = chatLog;
    }

    public MonopolyGame getMonopolyGame() {
        return monopolyGame;
    }

    public void setupMonopolyGame(GameScreenController gsc) {
        try {
            this.gameScreenController = gsc;
            GameMode mode = new GameMode(alliance, speedDie);
            this.monopolyGame = new MonopolyGame(players, gsc, mode);
            this.seed = monopolyGame.getDice().getGameSeed();
            gsc.setMonopolyClient(this);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }

    public void updateLobbyControllers() {
        boolean[] checkboxes = new boolean[]{alliance, speedDie, privateLobby};
        Player player = getById(getId());
        client.sendTCP(checkboxes);
        client.sendTCP(player);
    }

    public boolean getSpeedDie() {
        return speedDie;
    }

    public boolean getAlliance() {
        return alliance;
    }

    public boolean getPrivateLobby() {
        return privateLobby;
    }

    public void setAlliance(boolean alliance) {
        this.alliance = alliance;
    }

    public void setPrivateLobby(boolean privateLobby) {
        this.privateLobby = privateLobby;
    }

    public void setSpeedDie(boolean speedDie) {
        this.speedDie = speedDie;
    }

    public void sendObject(Object o) {
        connection.sendTCP(o);
    }
}
