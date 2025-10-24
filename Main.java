import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class Main {

    enum RegionType { SKY, FOREST, WATERS, VILLAGES, MOUNTAINS }
    enum ResourceType { WOOD, STONE, GLASS, FORCE }

    interface Action {
        int playerId();
        long timestamp();
    }

    static abstract class BaseAction implements Action {
        final int playerId;
        final long ts = System.currentTimeMillis();

        BaseAction(int playerId) { 
            this.playerId = playerId; 
        }

        public int playerId() { 
            return playerId; 
        }

        public long timestamp() { 
            return ts; 
        }
    }

    static final class PlaceStartingHouse extends BaseAction {
        final int x, y;

        PlaceStartingHouse(int p,int x,int y) { 
            super(p); 
            this.x = x; 
            this.y = y; 
        }

        public String toString() { 
            return "PlaceStartingHouse[p="+playerId+",("+x+","+y+")]"; 
        }
    }

    static final class Build extends BaseAction {
        final int x, y;

        Build(int p, int x, int y) { 
            super(p); 
            this.x = x; 
            this.y = y; 
        }

        public String toString() { 
            return "Build[p="+playerId+",("+x+","+y+")]"; 
        }
    }

    static final class EndTurn extends BaseAction {
        EndTurn(int p) { 
            super(p); 
        }

        public String toString() { 
            return "EndTurn[p="+playerId+"]"; 
        }
    }

    static final class ResourceGain implements Action {
        public int playerId(){ 
            return -1; 
        }

        public long timestamp(){ 
            return System.currentTimeMillis(); 
        }

        public String toString(){ 
            return "RainTick"; 
        }
    }

    static final class MatchState {
        final int width, height, players;
        final RegionType[][] regionMap;
        final int[][] houseOwner;
        final boolean[] placedStartingHouse;
        final int[][] resources; // resources[playerId][resource]
        final int[] alive;
        int currentTurn;
        volatile int winner = -1;

        final EnumMap<RegionType,int[]> buildCost = new EnumMap<>(RegionType.class);

        MatchState(int width,int height,int players,RegionType[][] map,int start) {
            this.width=width; 
            this.height=height; 
            this.players=players;
            this.regionMap = map;
            this.houseOwner = new int[height][width];
            for(int y = 0; y < height; y++) 
                Arrays.fill(houseOwner[y], -1);
            this.placedStartingHouse = new boolean[players];
            this.resources = new int[players][ResourceType.values().length];
            this.alive = new int[players]; 
            Arrays.fill(alive,1);
            for(int p = 0; p < players; p++) 
                Arrays.fill(resources[p], 2);
            this.currentTurn = start;

            putCost(RegionType.SKY, cost(1,1,0,2));
            putCost(RegionType.FOREST, cost(2,2,0,0));
            putCost(RegionType.WATERS, cost(1,1,2,1));
            putCost(RegionType.VILLAGES, cost(2,2,1,0));
            putCost(RegionType.MOUNTAINS, cost(0,3,0,2));
        }

        private void putCost(RegionType r,int[] c){ 
            buildCost.put(r,c); 
        }

        private static int[] cost(int wood,int stone,int glass,int force){
            int[] c = new int[ResourceType.values().length];
            c[ResourceType.WOOD.ordinal()]=wood;
            c[ResourceType.STONE.ordinal()]=stone;
            c[ResourceType.GLASS.ordinal()]=glass;
            c[ResourceType.FORCE.ordinal()]=force;
            return c;
        }

        boolean inBounds(int x,int y){ 
            return x>=0 && x<width && y>=0 && y<height; 
        }

        boolean isEmpty(int x,int y){ 
            return houseOwner[y][x]==-1; 
        }

        RegionType regionAt(int x,int y){ 
            return regionMap[y][x]; 
        }
    }

    static final class Game implements Runnable {
        private final LinkedBlockingQueue<Action> q;
        private final MatchState st;
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final Random rnd = new Random(42);

        Game(LinkedBlockingQueue<Action> q, MatchState st) {
            this.q = q; 
            this.st = st;
        }

        public void submit(Action a) { 
            q.offer(a); 
        }
        public void stop() { 
            running.set(false); 
        }

        @Override 
        public void run(){
            log("Engine started. Current turn: P" + st.currentTurn);
            while(running.get()) {
                try{
                    if(st.winner != -1) 
                        break;
                    Action a = q.poll(200, TimeUnit.MILLISECONDS);
                    if(a == null) 
                        continue;
                    apply(a);
                }catch(InterruptedException ie){
                    Thread.currentThread().interrupt(); 
                    break;
                }catch(Exception e){
                    log("ERROR: "+e);
                }
            }
            log("Engine stopped.");
        }

        private void apply(Action a){
            if(st.winner != -1) return;
            if(a instanceof PlaceStartingHouse p) 
                handlePlace(p);
            else if(a instanceof Build b)         
                handleBuild(b);
            else if(a instanceof EndTurn et)      
                handleEndTurn(et);
            else if(a instanceof ResourceGain)        
                handleResourceGain();
        }

        private void handlePlace(PlaceStartingHouse a){
            int p = a.playerId;
            if(!st.inBounds(a.x,a.y)) { 
                reject(a,"OUT_OF_BOUNDS"); 
                return; 
            }
            if(!st.isEmpty(a.x,a.y)) { 
                reject(a,"TILE_OCCUPIED"); 
                return; 
            }
            if(st.placedStartingHouse[p]) { 
                reject(a,"ALREADY_PLACED_STARTING_HOUSE"); 
                return;
            }
            st.houseOwner[a.y][a.x] = p;
            st.placedStartingHouse[p] = true;
            logOk(a,"Placed at ("+a.x+","+a.y+") in "+st.regionAt(a.x,a.y));
            checkVictory(p);
        }

        private void handleBuild(Build a){
            int p = a.playerId;
            if(p != st.currentTurn) { 
                reject(a,"OUT_OF_TURN (current: P"+st.currentTurn+")"); 
                return; 
            }
            if(!st.inBounds(a.x,a.y)) { 
                reject(a,"OUT_OF_BOUNDS"); 
                return; 
            }
            if(!st.isEmpty(a.x,a.y)) { 
                reject(a,"TILE_OCCUPIED"); 
                return; 
            }
            RegionType r = st.regionAt(a.x,a.y);
            int[] cost = st.buildCost.get(r);
            if(!has(p,cost)){ 
                reject(a,"INSUFFICIENT_RESOURCES for "+r); 
                return; 
            }
            pay(p,cost);
            st.houseOwner[a.y][a.x] = p;
            logOk(a,"Built at ("+a.x+","+a.y+") in "+r);
            checkVictory(p);
        }

        private void handleEndTurn(EndTurn a){
            int p = a.playerId;
            if(p != st.currentTurn) { 
                reject(a,"NOT_YOUR_TURN"); 
                return; 
            }
            advanceTurn();
            logOk(a,"Turn ended. Now P"+st.currentTurn+"'s turn.");
        }

        private void handleResourceGain(){
            int[][] countByType = new int[st.players][RegionType.values().length];
            for(int y = 0; y < st.height; y++) {
                for(int x = 0; x < st.width; x++) {
                    int owner = st.houseOwner[y][x];
                    if(owner >= 0) {
                        RegionType rt = st.regionAt(x,y);
                        countByType[owner][rt.ordinal()]++;
                    }
                }
            }

            int[][] gain = new int[st.players][ResourceType.values().length];
            for(int y = 0; y < st.height; y++) {
                for(int x = 0; x < st.width; x++){
                    int owner = st.houseOwner[y][x];
                    if(owner < 0) continue;
                    RegionType rt = st.regionAt(x,y);

                    ResourceType baseRes = switch(rt){
                        case SKY       -> ResourceType.FORCE;
                        case FOREST    -> ResourceType.WOOD;
                        case WATERS    -> ResourceType.GLASS;
                        case VILLAGES  -> (rnd.nextBoolean() ? ResourceType.WOOD : ResourceType.STONE);
                        case MOUNTAINS -> ResourceType.STONE;
                    };

                    int bonus = (countByType[owner][rt.ordinal()] >= 2) ? 1 : 0;
                    gain[owner][baseRes.ordinal()] += (1 + bonus);
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("[RESOURCE] ");
            for(int p = 0; p < st.players; p++) {
                boolean any = false;
                for(int r = 0; r < ResourceType.values().length; r++) {
                    int g = gain[p][r];
                    if(g > 0){
                        st.resources[p][r] += g;
                        if(any) 
                            sb.append(", ");
                        sb.append("P").append(p).append(" +").append(g).append(" ").append(ResourceType.values()[r]);
                        any = true;
                    }
                }
                if(any) 
                    sb.append(" | ");
            }
            if(sb.toString().equals("[RESOURCE] ")) 
                sb.append("no houses yet");
            System.out.println(sb.toString());
        }

        private void advanceTurn(){
            int next = st.currentTurn;
            for(int i = 0; i < st.players; i++){
                next = (next + 1) % st.players;
                if(st.alive[next] == 1) { 
                    st.currentTurn = next; 
                    return; 
                }
            }
        }

        private boolean has(int p,int[] c){
            for(int i = 0; i < c.length; i++) 
                if(st.resources[p][i] < c[i]) 
                    return false;
            return true;
        }

        private void pay(int p,int[] c){
            for(int i = 0; i < c.length; i++) 
                st.resources[p][i] -= c[i];
        }

        private void checkVictory(int p){
            EnumSet<RegionType> owned = EnumSet.noneOf(RegionType.class);
            for(int y = 0; y < st.height; y++)
                for(int x = 0; x < st.width; x++)
                    if(st.houseOwner[y][x] == p) 
                        owned.add(st.regionAt(x,y));
            if(owned.size() == RegionType.values().length) {
                st.winner = p;
                log("GAME OVER: P"+p+" owns all region types.");
                running.set(false);
            }
        }

        private void log(String m) { 
            System.out.println("[ENGINE] "+m); 
        }

        private void logOk(Action a,String d) { 
            System.out.println("[OK] "+a+" -> "+d); 
        }

        private void reject(Action a,String r) { 
            System.out.println("[REJECT] "+a+" -> "+r); 
        }
    }

    static final class PlayerBot implements Runnable {
        private final int id;
        private final Game engine;
        private final MatchState st;
        private final Random rnd;

        PlayerBot(int id, Game engine, MatchState st, long seed){
            this.id = id; 
            this.engine = engine; 
            this.st = st; 
            this.rnd = new Random(seed+id);
        }

        @Override 
        public void run(){
            while(!st.placedStartingHouse[id] && st.winner == -1) {
                int[] cell = randomFreeCell();
                if(cell != null) 
                    engine.submit(new PlaceStartingHouse(id, cell[0], cell[1]));
                sleep(50,120);
            }

            while(st.winner == -1) {
                if(st.currentTurn == id) {
                    if(rnd.nextDouble() < 0.7) {
                        int[] cell = randomAffordableFreeCell();
                        if(cell!=null) 
                            engine.submit(new Build(id, cell[0], cell[1]));
                        else 
                            engine.submit(new EndTurn(id));
                    } else {
                        engine.submit(new EndTurn(id));
                    }
                    sleep(80,160);
                } else {
                    sleep(60,120);
                }
            }
        }

        private int[] randomFreeCell() {
            List<int[]> free = new ArrayList<>();
            for(int y = 0; y< st.height; y++)
                for(int x = 0; x < st.width; x++)
                    if(st.houseOwner[y][x] == -1) 
                        free.add(new int[]{x,y});
            if(free.isEmpty()) 
                return null;
            return free.get(rnd.nextInt(free.size()));
        }

        private int[] randomAffordableFreeCell() {
            List<int[]> candidates = new ArrayList<>();
            for (int y = 0; y < st.height; y++) {
                for (int x = 0; x < st.width; x++) {
                    if (st.houseOwner[y][x] != -1) continue;
                    var rt = st.regionAt(x, y);
                    int[] cost = st.buildCost.get(rt);
                    if (canAfford(id, cost)) candidates.add(new int[]{x, y});
                }
            }
            if (candidates.isEmpty()) return null;
            return candidates.get(rnd.nextInt(candidates.size()));
        }

        private boolean canAfford(int p, int[] cost) {
            for (int i = 0; i < cost.length; i++) {
                if (st.resources[p][i] < cost[i]) return false;
            }
            return true;
        }

        private void sleep(int lo,int hi){
            try { 
                Thread.sleep(lo + rnd.nextInt(Math.max(1,hi-lo))); 
            } catch (InterruptedException e) {}
        }
    }

    static RegionType[][] createMap(){
        return new RegionType[][] {
            { RegionType.SKY,    RegionType.FOREST,    RegionType.WATERS,    RegionType.VILLAGES, RegionType.MOUNTAINS },
            { RegionType.FOREST, RegionType.WATERS,    RegionType.MOUNTAINS, RegionType.SKY,      RegionType.VILLAGES  },
            { RegionType.WATERS, RegionType.MOUNTAINS, RegionType.SKY,       RegionType.FOREST,   RegionType.VILLAGES  }
        };
    }

    private static int parseInt(String s,int def) { 
        try { 
            return Integer.parseInt(s); 
        } catch(Exception e) { 
            return def; 
        } 
    }

    public static void main(String[] args) throws Exception {
        final int players = (args.length > 0) ? Math.max(2, Math.min(8, parseInt(args[0], 4))) : 4;
        final int width = 5, height = 3;

        RegionType[][] map = createMap();
        LinkedBlockingQueue<Action> q = new LinkedBlockingQueue<>();
        MatchState st = new MatchState(width, height, players, map,0);
        Game engine = new Game(q, st);

        Thread engineThread = new Thread(engine, "engine");
        engineThread.start();

        List<Thread> bots = new ArrayList<>();
        long seed = System.nanoTime();
        for(int p = 0; p < players; p++) {
            Thread t = new Thread(new PlayerBot(p, engine, st, seed), "player-"+p);
            t.start();
            bots.add(t);
        }

        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(() -> engine.submit(new ResourceGain()), 2, 2, TimeUnit.SECONDS);

        engineThread.join();
        scheduler.shutdownNow();
        for(Thread t: bots) 
            t.join(200);

        System.out.println("Done.");
    }
}
