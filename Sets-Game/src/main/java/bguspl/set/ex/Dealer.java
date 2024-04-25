package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.ThreadLogger;

import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingDeque; // for claimSet
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {


    private String name = "dealer";
    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;


    public BlockingQueue<Integer> getClaimSets() {
        return claimSets;
    }

    // queue for all players that claim for set-in order
    private BlockingQueue<Integer> claimSets;

    //list of players thread
    private ThreadLogger[] playersThread;

    public static Object dealerlock = new Object();





    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        Collections.shuffle(deck);

        // create a queue for all players that claim set-in order
        claimSets = new LinkedBlockingDeque<>(players.length);
        // create a thread for each player
        playersThread = new ThreadLogger[players.length];
        reshuffleTime = env.config.turnTimeoutMillis;
        terminate = false;


    }


    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */

    @Override
    public void run() {
        System.out.println("Dealer thread started.");

        for (int num = 0; num < players.length; num++) {
            ThreadLogger playerThread = new ThreadLogger(players[num], env.config.playerNames[num], env.logger);
            playersThread[num] = playerThread;
            playerThread.startWithLog();
            System.out.println("Dealer started player " + num + " thread.");

        }
        while (!this.terminate) {
            placeCardsOnTable();
            timerLoop();
            updateTimerDisplay(true);
            try {
                removeAllCardsFromTable();
                if (shouldFinish())
                    terminate();
            } catch (InterruptedException e) {

            }


        }
        announceWinners();
        System.out.println("Dealer thread terminated.");

    }


    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        reshuffleTime = env.config.turnTimeoutMillis + System.currentTimeMillis(); //time to reshuffle the deck
        int i = 0;
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            if (i == 0) {
                table.hints();
                i++;
            }
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();


        }


    }

    /**
     * Called when the game should be terminated.
     */
    public  void  terminate() throws InterruptedException {
        // TODO implement



            this.terminate = true;


            for (int i = players.length-1; i >= 0; i--) {
                players[i].terminate();
                playersThread[i].join();
            }




    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        List<Integer> deckontable = Arrays.stream(table.slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        List<int[]> setontable = env.util.findSets(deckontable, 1);
        List<int[]> setsondeck = env.util.findSets(deck, 1);
        return (setontable.isEmpty() && setsondeck.isEmpty());
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        // TODO implement
        try {
            if (!claimSets.isEmpty()) {
                int playerid = claimSets.take(); //the player id we should remove the set cards froom thr table
                int[] playerCards = table.playerSetsCards(playerid); //get the player cards of the set
                if (table.playerTokensCountrs[playerid] == env.config.featureSize) {
                    if (legelSet(playerCards)) {

                        synchronized (players[playerid].playerLock) {

                            for (int i = 0; i < playerCards.length; i++) {
                                int card = playerCards[i];
                                int slot = table.cardToSlot[card];
                                table.removeTokens(slot);
                                table.removeCard(slot);
                                env.ui.removeCard(slot); //UI update


                            }

                            players[playerid].playerLock.notifyAll();
                        }

                        players[playerid].point();


                    } else {

                        for (int i = 0; i < playerCards.length; i++) {
                            int card = playerCards[i];
                            int slot = table.cardToSlot[card];
                            table.removeToken(playerid, slot);
                        }
                        players[playerid].penalty();
                        synchronized (players[playerid].playerLock) {
                            players[playerid].playerLock.notifyAll();
                        }
                    }
                }
                synchronized (players[playerid].playerLock) {
                    players[playerid].playerLock.notifyAll();
                }


            }
            if (shouldFinish())
                terminate();

        } catch (InterruptedException ignored) { //check if other thread is Interrupted

        }

    }


    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {

        Collections.shuffle(deck);
        List<Integer> emptySlots = new ArrayList<>();

        for (int i = 0; i < env.config.tableSize; i++)
        {
            if (table.slotToCard[i]==null)
                emptySlots.add(i);
        }

        Collections.shuffle(emptySlots);

        for (int i :emptySlots ) {
            if (deck.isEmpty()) {
                break; //should terminate?
            } else if (table.slotToCard[i] == null) {
                table.placeCard(deck.get(0), i);
                deck.remove(0);
                updateTimerDisplay(true);

            }
        }
        synchronized (dealerlock) {
            table.canplay = true;
            dealerlock.notifyAll();//when finish placing the card notify the players
        }


    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        synchronized (table.gameflowlock) {
            if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                try {
                    table.gameflowlock.wait(10);
                    if (shouldFinish())
                        terminate();

                } catch (InterruptedException ignored) {

                }
            } else {
                try {
                    table.gameflowlock.wait(1000);
                    if (shouldFinish())
                        terminate();

                } catch (InterruptedException ignored) {

                }
            }
        }
    }

        /**
         * Reset and/or update the countdown and the countdown display.
         */
        private void updateTimerDisplay ( boolean reset){
            // TODO implement
            if (!reset) {
                if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                    env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), true);
                } else
                    env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            } else {
                reshuffleTime = env.config.turnTimeoutMillis + System.currentTimeMillis();
                env.ui.setCountdown(env.config.turnTimeoutMillis, false);
            }
        }

        /**
         * Returns all the cards from the table to the deck.
         */
        private void removeAllCardsFromTable () throws InterruptedException {
            // TODO implement
            table.canplay = false;
            try {
                // check all the avalible sets on the Q
                while (!claimSets.isEmpty()) {
                    if (legelSet()) {
                        int player = claimSets.take(); //the player id we should remove the set cards from the tab
                        players[player].point();
                    } else {
                        int player = claimSets.take();
                        players[player].penalty();
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace(); // Handle or log the exception as needed
            }

            //remove all the tokenes
            for (int i = 0; i < table.playerTokensCountrs.length; i++) {  //initilize playerTokensCountrs
                table.playerTokensCountrs[i] = 0;
            }
            for (int i = 0; i < table.playersTokensSlots.length; i++) {   //initilize playersTokensSlots
                for (int j = 0; j < table.playersTokensSlots[i].length; j++) {
                    table.playersTokensSlots[i][j] = false;
                }
            }
            for (int i = 0; i < env.config.tableSize; i++) {  //UI update
                env.ui.removeTokens(i);
            }

            List<Integer> fullSlots = new ArrayList<>();

            for (int i = 0; i < env.config.tableSize; i++)
            {
                if (table.slotToCard[i]!=null)
                    fullSlots.add(i);
            }

            Collections.shuffle(fullSlots);


            //removing the cards and return them to the deck
            for (int i :fullSlots) {
                if (table.slotToCard[i] != null) {
                    int card = table.slotToCard[i];
                    table.removeCard(i);
                    deck.add(card);
                }
            }
            for (int i = 0; i < players.length; i++) {
                synchronized (players[i].playerLock) {
                    players[i].playerLock.notifyAll();
                }
            }

            if (shouldFinish())
                terminate();//call terminate function
        }


        /**
         * Check who is/are the winner/s and displays them.\
         */
        private void announceWinners () {
            // TODO implement
            int maxScore = 0;
            for (int i = 0; i < players.length; i++) {
                if (maxScore < players[i].score())
                    maxScore = players[i].score();
            }
            int numOfWinners = 0;
            for (int i = 0; i < players.length; i++) {
                if (maxScore == players[i].score())
                    numOfWinners++;
            }
            int[] winerrs = new int[numOfWinners];
            int j =0;
            for (int i = 0; i < players.length; i++) {
                if (players[i].score() == maxScore) {
                    winerrs[j] = i;
                    j++;
                }

            }
            env.ui.announceWinner(winerrs); //UI update

        }


        // Added by us!

        /**
         * checks for legal set for the rhe first player in the claimSets queue
         */

        private boolean legelSet () {
            if (!claimSets.isEmpty()) {
                int id = claimSets.element();
                int[] set = table.playerSetsCards(id);
                boolean isLegelSet = env.util.testSet(set);
                return isLegelSet;
            } else {
                return false;
            }


        }


        private boolean legelSet ( int[] set){
            boolean isLegelSet = env.util.testSet(set);
            return isLegelSet;

        }


    }


