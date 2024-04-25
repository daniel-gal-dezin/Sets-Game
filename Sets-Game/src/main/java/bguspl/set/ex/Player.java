package bguspl.set.ex;

import bguspl.set.Env;

import java.util.Collection;
import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;


/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private final Dealer dealer;


    public BlockingQueue<Integer> keys;

    public volatile boolean canplay = true;

    public  Object playerLock = new Object();
     public boolean sleepforpoint = false;
     public boolean sleepfrpanelty =false;


    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        keys = new LinkedBlockingQueue<Integer>(env.config.featureSize);
        this.score=0;


    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        System.out.println("Player " + id + " thread started.");

        if (!human) createArtificialIntelligence();

        while (!terminate) {
            synchronized (dealer.dealerlock){
            while (!table.canplay) {
                try {
                    dealer.dealerlock.wait();
                } catch (InterruptedException e) {
                    terminate();
                }
            }
            }
            if (!keys.isEmpty()) {
                int slot = keys.poll();

                if (table.playersTokensSlots[id][slot]) {
                    table.removeToken(id, slot);

                } else {
                    table.placeToken(id, slot);

                }
            }
            if (table.playerTokensCountrs[id] == env.config.featureSize) {

                synchronized (playerLock) {
                    try {

                        canplay = false;
                        dealer.getClaimSets().put(id);// here he need to sleep until dealer check is set
                    } catch (InterruptedException e) {
                        terminate();
                    }
                    try{

                        playerLock.wait();
                    }
                    catch(InterruptedException e){terminate();}
                }

                        canplay = true;
                    }

            if (sleepforpoint) {
                env.ui.setFreeze(id,env.config.pointFreezeMillis);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    terminate();
                }
                env.ui.setFreeze(id,0);
                sleepforpoint = false;
            }

            if (sleepfrpanelty) {
                for(long i = env.config.penaltyFreezeMillis/1000; i>0; i--){

                    env.ui.setFreeze(id,i*1000);
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException ignored) {
                        terminate();
                    }

                }
                env.ui.setFreeze(id,0);
                sleepfrpanelty = false;
            }
        }
        if (!human) try { aiThread.join(); } catch (InterruptedException ignored) {}
        System.out.println("Player " + id + " thread terminated.");
    }



    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
 private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            while (!terminate) {
                // TODO implement player key press simulator
                int randomNum = (int)(Math.random() * (env.config.tableSize));
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                terminate();
                    
                }
                keyPressed(randomNum);


            }
        }, "computer-" + id);
        aiThread.start();
    }  

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        // TODO implement
        terminate = true;
        playerThread.interrupt();

    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
            if(keys.size() < env.config.featureSize && this.canplay){
                try{

                    keys.put(slot);}
                catch(InterruptedException ignored) {terminate();}
            }
        }


    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        sleepforpoint = true;
        env.ui.setFreeze(id,env.config.pointFreezeMillis);
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
        env.ui.setScore(id, ++score);
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {

        sleepfrpanelty = true;


    }

    public int score() {
        return score;
    }
}
