package control;

import control.action.*;
import entity.Board;
import entity.Player;
import entity.card.Card;
import entity.dice.Dice;
import entity.dice.DiceResult;
import entity.property.Building;
import entity.property.Dorm;
import entity.property.Facility;
import entity.property.Property;
import entity.tile.*;
import gui.GameScreenController;

// how does ui and this communicate?
// UI and UIController class
// UIControl class will have both UI and MonopolyGame
// It will call appropriate functions according to the user input

// about multiplayer
// return ArrayList<Action> to server
// server will send these actions to other clients
// clients will process these actions

import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;
import java.util.Random;

public class MonopolyGame {

    Board board;
    int turn;
    public static ActionLog actionLog;
    PlayerController playerController;
    GameMode mode;
    /**
     * use int to represent the state of a mr monopoly usage
     * 0 : dice result was not mr monopoly
     * 1 : dice result is mr monopoly, playing current turn
     * 2 : dice result was mr monopoly and playing mr monopoly turn
     */
    int mrMonopoly = 0;
    boolean gameStarted = false;
    boolean gamePaused = false;
    int doubleCount = 0;
    int moveCount = 0; // sum of dices
    Dice dice;
    DiceResult diceResult;
    GameScreenController ui;


    public MonopolyGame(ArrayList<Player> players, GameScreenController ui, GameMode mode) throws IOException {
        board = new Board();
        turn = 0;
        actionLog = ActionLog.getInstance();
        playerController = new PlayerController(players);
        dice = new Dice(System.currentTimeMillis());
        this.ui = ui;
        this.mode = mode; // set the game mode accordingly
    }

    public MonopolyGame() throws IOException {
        board = new Board();
        turn = 0;
        actionLog = ActionLog.getInstance();
        dice = new Dice(System.currentTimeMillis());
    }


    public MonopolyGame(ArrayList<Player> players, long seed, GameMode mode) throws IOException {
        board = new Board();
        turn = 0;
        actionLog = ActionLog.getInstance();
        playerController = new PlayerController(players);
        dice = new Dice(seed);
        this.mode = mode; // set the game mode accordingly
    }

    public void addPlayer(Player player) {
        playerController.addPlayer(player);
    }

    public void stopGame() {
        gameStarted = false;
    }

    public Player startGame() {
        ArrayList<Player> players = playerController.getPlayers();
        System.out.println("Size of players: " + players.size());
        playerController.setActivePlayer(players.get(new Random().nextInt(players.size()))); // randomize the process, it creates int with size, how??
        gameStarted = true;

        return playerController.getActivePlayer();
    }

    public void rollDice() {
        diceResult = dice.roll(mode.isSpeedDie());
        if (diceResult.getSpeedDieResult().isMrMonopoly())
            mrMonopoly = 1; // enter state 1

        if ( diceResult.isDouble() ) {
            doubleCount++;
        }

        Player player = getActivePlayer();
        Action action = new RollDiceAction(player, diceResult);
        action.act();
        ui.sendAction(action);
        ui.sendObject(diceResult);

        moveCount = computeMoveCount();
    }

    public void processTurn() {
        Player player = playerController.getActivePlayer();

        if (doubleCount == 3) { // if this is the third double, put player into the jail
            GoToJailAction action = new GoToJailAction(player.getPlayerId());
            action.act();
            ui.sendAction(action);
            doubleCount = 0;
        }
        else if ((diceResult.isDouble() && player.isInJail()) || (player.getJailTurnCount() == 2)) {
            GetOutOfJailAction action = new GetOutOfJailAction(player.getPlayerId());
            action.act();
            ui.sendAction(action);
            player.setJailTurnCount(0);
        }
        if (!player.isInJail()) {
            if (moveCount == -1) {
                moveCount = ui.showBusDialog(diceResult);
                MoveAction action = new MoveAction(player.getPlayerId(), moveCount); // try catch? PlayerIsInJailException
                action.act();
                ui.sendAction(action);
                ui.updateBoardState();
                Tile tile = board.getTiles().get(player.getPosition());
                processTile(tile, player);
            }
            else {
                // normal move of a player made here
                // in order for mr monopoly and normal game mode to run at the same time
                // use states for mr monopoly
                int whiteDice = diceResult.getValue();
                if (mrMonopoly == 1) {
                    // state 1 -
                    // player resolves their white dice first
                    // if they rolled a mr monopoly
                    mrMonopoly = 2;
                }
                MoveAction action = new MoveAction(player.getPlayerId(), whiteDice); // try catch? PlayerIsInJailException
                action.act();
                ui.sendAction(action);
                ui.updateBoardState();
                Tile tile = board.getTiles().get(player.getPosition());
                processTile(tile, player);

                // it is also considered that player can't use mr. monopoly
                // if they are inside the jail
                if (!player.isInJail() && mrMonopoly == 2) {
                    // if mr monopoly is not used, now it is time to use it
                    moveCount = computeMrMonopoly();
                    MoveAction mrAction = new MoveAction(player.getPlayerId(), moveCount); // try catch? PlayerIsInJailException
                    mrAction.act();
                    ui.sendAction(mrAction);
                    ui.updateBoardState();
                    Tile mrTile = board.getTiles().get(player.getPosition());
                    processTile(mrTile, player);
                    mrMonopoly = 0;
                }
            }
        }
        else {
            player.setJailTurnCount(player.getJailTurnCount() + 1);
        }
        nextTurn();
        ui.updateBoardState();

    }

    public void nextTurn() {
        if (!isGameOver()) {
            if ( doubleCount == 3  ||  getActivePlayer().isBankrupt() || !diceResult.isDouble() ) {
                playerController.switchToNextPlayer();
                ui.sendObject("next player:" + playerController.getActivePlayerIndex());
                doubleCount = 0;
            }
            turn++;
        }
        else {
            // determine the winner and announce
            Player p = null;
            for (Player player : playerController.getPlayers() ) {
                if (!player.isBankrupt()) {
                    p = player;
                    break;
                }
            }

            ui.sendObject("winner:" + p.getPlayerId());
        }
    }

    public void processPropertyTile(PropertyTile tile) {
        Property property = board.getPropertyById(tile.getPropertyId());

        // actionLog.addMessage(getActivePlayer().getName() + " lands on " + property.getName() + "\n");
        if (!property.isOwned() && getActivePlayer().getBalance() >= property.getPrice()) {
            boolean playerBoughtProperty = ui.showPropertyDialog(property); // separate the house and hotel dialog

            if (playerBoughtProperty) {
                BuyPropertyAction action = new BuyPropertyAction(property.getId(), getActivePlayer().getPlayerId());
                action.act();
                ui.sendAction(action);
            }
            else {
                // auction --> not implemented
            }
        }
        else if ( property.isOwned() && getActivePlayer().getPlayerId() != property.getOwnerId() ){
            int transferAmount = 0;
            Player propertyOwner = playerController.getById(property.getOwnerId());
            System.out.println("Property owner: " + propertyOwner.getName());
            if (mode.isAlliance()) {
                int ownerTeamNo = propertyOwner.getTeamNumber();
                int activeTeamNo = getActivePlayer().getTeamNumber();
                boolean diffTeams = mode.isAlliance() && (ownerTeamNo != activeTeamNo);
                if (diffTeams) {
                    payRent(property, transferAmount, propertyOwner);
                }
            }
            else {
                payRent(property, transferAmount, propertyOwner);
            }

        }
    }

    private void payRent(Property property, int transferAmount, Player propertyOwner) {
        if (property instanceof Dorm) {
            System.out.println("Dorm count: " + propertyOwner.getProperties().get("DORM").size());
            if ( propertyOwner.getProperties().get("DORM").size() == 1 ) {
                transferAmount = 2500;
            }
            else if (propertyOwner.getProperties().get("DORM").size() == 2 ){
                transferAmount = 5000;
            }
            else if ( propertyOwner.getProperties().get("DORM").size() == 3 ) {
                transferAmount = 10000;
            }
            else if ( propertyOwner.getProperties().get("DORM").size() == 4 ) {
                transferAmount = 20000;
            }
        }
        else if (property instanceof Facility) {
            int diceTotal = diceResult.getValue();
            if ( propertyOwner.getProperties().get("FACILITY").size() == 1 ) {
                transferAmount = diceTotal * 400;
            }
            else if ( propertyOwner.getProperties().get("FACILITY").size() == 2 ) {
                transferAmount = diceTotal * 1000;
            }
        }
        else if (property instanceof Building) {
            Building building = (Building) property;
            boolean isComplete = propertyOwner.isComplete(building);

            if ( building.getClassroomCount() == 0 && !isComplete ) {
                transferAmount = building.getRents().get(0);
            }
            else if ( building.getClassroomCount() == 0 && isComplete ) {
                transferAmount = building.getRents().get(0) * 2;
            }
            else if ( building.getLectureHallCount() == 1 ) {
                transferAmount = building.getRents().get(5);
            }
            else if ( building.getClassroomCount() == 1 ) {
                transferAmount = building.getRents().get(1);
            }
            else if ( building.getClassroomCount() == 2 ) {
                transferAmount = building.getRents().get(2);
            }
            else if ( building.getClassroomCount() == 3 ) {
                transferAmount = building.getRents().get(3);
            }
            else if ( building.getClassroomCount() == 4 ) {
                transferAmount = building.getRents().get(4);
            }
        }

        if (getActivePlayer().getBalance() < transferAmount) {
            // ToDo: start trade process or bankrupt the player, player should choose
            // boolean bankruptAnswer = ui.showBankruptDialog(getActivePlayer(), transferAmount);
            bankruptPlayer(getActivePlayer());
        }
        else {
            TransferAction transferAction = new TransferAction(getActivePlayer().getPlayerId(), propertyOwner.getPlayerId(), transferAmount);
            transferAction.act();
            ui.sendAction(transferAction);
        }

    }

    public void bankruptPlayer(Player p) {
        System.out.println("called bankrupt player");
        BankruptPlayerAction bankruptPlayerAction = new BankruptPlayerAction(p.getPlayerId());
        bankruptPlayerAction.act();
        ui.sendAction(bankruptPlayerAction);
        for (Map.Entry<String, ArrayList<Property>> entry : p.getProperties().entrySet()) {
            ArrayList<Property> properties = entry.getValue();
            for (Property property : properties) {
                RemovePropertyAction action = new RemovePropertyAction(p.getPlayerId(), property.getId());
                action.act();
                ui.sendAction(action);
            }
        }
        RemoveMoneyAction action = new RemoveMoneyAction(p.getPlayerId(), p.getBalance());
        action.act();
        ui.sendAction(action);
    }

    public Card processChanceCardTile() {
        Card card = board.drawChanceCard();
        DrawChanceCardAction action = new DrawChanceCardAction(getActivePlayer(), card);
        action.act();
        ui.sendAction(action);
        processCard(card);

        return card;
    }

    public Card processCommunityChestCardTile() {
        Card card = board.drawCommunityChestCard();
        DrawCommunityChestCardAction action = new DrawCommunityChestCardAction(getActivePlayer(), card);
        action.act();
        ui.sendAction(action);
        processCard(card);

        return card;
    }

    public void processCard(Card card) {
        Player activePlayer = getActivePlayer();
        switch (card.getId()) {
            case 0:
                FreeMoveAction freeMoveAction2 = new FreeMoveAction(activePlayer.getPlayerId(), 1); // L Building --> position = 1
                freeMoveAction2.act();
                ui.sendAction(freeMoveAction2);
                //monopolyGame.processTurn();
                break;
            case 1:
                AddMoneyAction addMoneyAction = new AddMoneyAction(activePlayer.getPlayerId(), 5_000);
                addMoneyAction.act();
                ui.sendAction(addMoneyAction);
                break;
            case 2:
                AddMoneyAction addMoneyAction2 = new AddMoneyAction(activePlayer.getPlayerId(), 1_000);
                addMoneyAction2.act();
                ui.sendAction(addMoneyAction2);
                break;
            case 3:
                RemoveMoneyAction removeMoneyAction = new RemoveMoneyAction(activePlayer.getPlayerId(), 10_000);
                removeMoneyAction.act();
                ui.sendAction(removeMoneyAction);
                break;
            case 4:
            case 8: // duplicate card with 4
                FreeMoveAction freeMoveAction = new FreeMoveAction(activePlayer.getPlayerId(), 0);
                freeMoveAction.act();
                ui.sendAction(freeMoveAction);
                break;
            case 5:
                for (Player p : getPlayerController().getPlayers() )
                    if ( p.getPlayerId() != activePlayer.getPlayerId() ) {
                        TransferAction transferAction = new TransferAction(p.getPlayerId(), activePlayer.getPlayerId(), 1_000);
                        transferAction.act();
                        ui.sendAction(transferAction);
                    }
                break;
            case 6:
            case 11:
                GoToJailAction goToJailAction = new GoToJailAction(activePlayer.getPlayerId()); // player moves again if he threw double before this
                goToJailAction.act();
                ui.sendAction(goToJailAction);
                break;
            case 7:
                FreeMoveAction freeMoveAction3 = new FreeMoveAction(activePlayer.getPlayerId(), 34); // Starbucks is in 23th tile
                freeMoveAction3.act();
                ui.sendAction(freeMoveAction3);
                break;
            case 9:
                MoveAction moveAction = new MoveAction(activePlayer.getPlayerId(), -3);
                moveAction.act();
                ui.sendAction(moveAction);
                //monopolyGame.processTurn();
                break;
            case 10:
                FreeMoveAction freeMoveAction1 = new FreeMoveAction(activePlayer.getPlayerId(), 21); // Kirac is in 23th tile
                freeMoveAction1.act();
                ui.sendAction(freeMoveAction1);
                //monopolyGame.processTurn();
                break;
            case 12:
                FreeMoveAction freeMoveAction4 = new FreeMoveAction(activePlayer.getPlayerId(),39); // Library is the last tile (39th)
                freeMoveAction4.act();
                ui.sendAction(freeMoveAction4);
                //monopolyGame.processTurn();
                break;
            case 13:
                AddMoneyAction addMoneyAction1 = new AddMoneyAction(activePlayer.getPlayerId(), 10_000);
                addMoneyAction1.act();
                ui.sendAction(addMoneyAction1);
                break;
            case 14:
                RemoveMoneyAction removeMoneyAction1 = new RemoveMoneyAction(activePlayer.getPlayerId(), 2_000);
                removeMoneyAction1.act();
                ui.sendAction(removeMoneyAction1);
                break;
            case 15:
                int houseCount = 0;
                int hotelCount = 0;
                int repairAmount = 0;
                ArrayList<Property> properties = activePlayer.getProperties().get("BUILDING");
                for (Property p : properties ) {
                    houseCount = houseCount + ((Building) p).getClassroomCount();
                    hotelCount = hotelCount + ((Building) p).getLectureHallCount();
                }
                repairAmount = (4_000 * houseCount) + (11_500 * hotelCount);
                RemoveMoneyAction removeMoneyAction2 = new RemoveMoneyAction(activePlayer.getPlayerId(), repairAmount);
                removeMoneyAction2.act();
                ui.sendAction(removeMoneyAction2);
                break;
        }
    }

    public boolean isGameOver() {
        ArrayList<Player> players = playerController.getPlayers();

        int bankruptPlayerCount = 0;

        for (Player p : players) {
            if (p.isBankrupt()) {
                bankruptPlayerCount++;
            }
        }

        return bankruptPlayerCount == players.size() - 1;
    }


    /**
     * private helper function to process a tile
     * in simple manners, just like in the activity
     * diagram Resolve tile
     * @param tile
     * @param player
     */
    private void processTile(Tile tile, Player player) {
        if (tile instanceof PropertyTile) {
            processPropertyTile((PropertyTile) board.getTiles().get(player.getPosition()));
        }
        else if (tile instanceof JailTile || tile instanceof FreeParkingTile) {
            // nothing, skip the turn
        }
        else if (tile instanceof StartTile) {
            // new PassAction(player).act();
        }
        else if (tile instanceof TaxTile) {
            TaxTile taxTile = (TaxTile) tile;
            if (getActivePlayer().getBalance() < taxTile.getAmount()) {
                bankruptPlayer(getActivePlayer());
            }
            else {
                RemoveMoneyAction action2 = new RemoveMoneyAction(player.getPlayerId(), taxTile.getAmount());
                action2.act();
                ui.sendAction(action2);
            }
        }
        else if (tile instanceof GoToJailTile) {
            GoToJailAction action2 = new GoToJailAction(player.getPlayerId());
            action2.act();
            ui.sendAction(action2);
        }
        else if (tile instanceof CardTile) {
            CardTile cardTile = (CardTile) tile;
            if (cardTile.getCardType() == CardTile.CardType.CHANCE_CARD)
                processChanceCardTile();
            else
                processCommunityChestCardTile();
        }
    }

    /**
     * Private helper function that enumerates special cases of speed die
     * if there is no special speed die behaviour
     * it returns the sum
     * @return -1 for BUS, -2 for Mr. Monopoly, -3 for triple
     */
    private int computeMoveCount() {
        if (diceResult.getSpeedDieResult().isBus()) {
            return -1;
        }
        else if (diceResult.getSpeedDieResult().isMrMonopoly() ) {
            return -2;
        }
        else if (diceResult.isTriple()) {
            return -3;
        }
        else {
            return diceResult.getValue();
        }
    }

    /**
     * The algorithm that computes the number of moves
     * the player needs to make to use MrMonopoly
     * There is a slight chance that the player
     * has no chance to move on next property
     * so they stay where they are
     * @return number of moves, return 0 for the edge case
     */
    private int computeMrMonopoly() {
        int activePID = playerController.getActivePlayer().getPlayerId();
        int initActivePPos = PlayerController.getById(activePID).getPosition();
        int moveAmount = 0;
        // Iterate through the board, start at active players position,
        // stop when a lap is made and take module of # of tiles so that tileID is always found
        // even if player passes the start tile
        for (int activePPos = initActivePPos + 1;
             activePPos != initActivePPos;
             activePPos = (activePPos + 1) % board.getTiles().size() )
        {
            moveAmount++;
            Property posProp = getPropertyByTileID(activePPos);
            System.out.println("posProp --> " + posProp);
            // Skip tiles other than property tiles
            if (posProp != null) {
                // Case 1: No property is left in the bank
                // Player moves to the next property where he/she will pay rent
                if (board.isAllOwned()) {
                    // if the players are different AND diff. teams AND unmortgaged
                    int ownerID = posProp.getOwnerId();
                    Player owner = PlayerController.getById(ownerID);
                    boolean diffPlayers = ownerID != activePID;
                    boolean diffTeams = getActivePlayer().getTeamNumber() != owner.getTeamNumber();
                    boolean mortgaged = posProp.isMortgaged();
                    if (diffPlayers && diffTeams && !mortgaged) {
                        return moveAmount;
                    }
                }
                // Case 2 : There are unowned properties left in the bank
                // Player moves to the next unowned property
                else {
                    if (!posProp.isOwned()) {
                        return moveAmount;
                    }
                }
            }
        }
        // IF there is literally no property
        // to pay rent in the game player doesn't move
        return 0;
    }

    /**
     * Gets the property of a given tile ID if a given tile ID is a PropertyTile
     * Else, returns null
     * @param tileID ID of the given tile
     * @return Property for that tile, else null
     */
    private Property getPropertyByTileID(int tileID) {
        Tile curTile = board.getTiles().get(tileID);
        System.out.println("tileID : " + tileID);
        if (curTile instanceof PropertyTile) {
            return Board.getPropertyById(((PropertyTile) curTile).getPropertyId());
        }
        return null;
    }

    public void update() {
        ui.updateBoardState();
    }

    public Player getActivePlayer() {return playerController.getActivePlayer();}

    public Board getBoard() {
        return board;
    }

    public void setBoard(Board board) {
        this.board = board;
    }

    public int getTurn() {
        return turn;
    }

    public void setTurn(int turn) {
        this.turn = turn;
    }

    public static ActionLog getActionLog() {
        return ActionLog.getInstance();
    }

    public void setActionLog(ActionLog actionLog) {
        MonopolyGame.actionLog = actionLog;
    }

    public PlayerController getPlayerController() {
        return playerController;
    }

    public void setPlayerController(PlayerController playerController) {
        this.playerController = playerController;
    }

    public Dice getDice() {
        return dice;
    }

    public void setDice(Dice dice) {
        this.dice = dice;
    }

    public boolean isGameStarted() {
        return gameStarted;
    }

    public void setGameStarted(boolean gameStarted) {
        this.gameStarted = gameStarted;
    }

    public GameScreenController getUi() {
        return ui;
    }

    public void setUi(GameScreenController ui) {
        this.ui = ui;
    }

    public GameMode getGameMode() {
        return this.mode;
    }

}
