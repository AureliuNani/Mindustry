package mindustry.game;

import arc.*;
import arc.func.*;
import arc.graphics.*;
import arc.graphics.g2d.*;
import arc.math.*;
import arc.math.geom.*;
import arc.scene.ui.layout.*;
import arc.struct.*;
import arc.util.*;
import mindustry.*;
import mindustry.content.*;
import mindustry.ctype.*;
import mindustry.game.MapObjectives.*;
import mindustry.gen.*;
import mindustry.graphics.*;
import mindustry.io.*;
import mindustry.logic.*;
import mindustry.type.*;
import mindustry.world.*;

import java.lang.annotation.*;
import java.util.*;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.*;
import static mindustry.Vars.*;

/** Handles and executes in-map objectives. */
public class MapObjectives implements Iterable<MapObjective>, Eachable<MapObjective>{
    public static final Seq<Prov<? extends MapObjective>> allObjectiveTypes = new Seq<>();
    public static final Seq<Prov<? extends ObjectiveMarker>> allMarkerTypes = new Seq<>();
    public static final ObjectMap<String, Prov<? extends ObjectiveMarker>> markerNameToType = new ObjectMap<>();
    public static final Seq<String> allMarkerTypeNames = new Seq<>();

    /**
     * All objectives the executor contains. Do not modify directly, ever!
     * @see #eachRunning(Cons)
     */
    public Seq<MapObjective> all = new Seq<>(4);
    /** @see #checkChanged() */
    protected transient boolean changed;

    static{
        registerObjective(
            ResearchObjective::new,
            ProduceObjective::new,
            ItemObjective::new,
            CoreItemObjective::new,
            BuildCountObjective::new,
            UnitCountObjective::new,
            DestroyUnitsObjective::new,
            TimerObjective::new,
            DestroyBlockObjective::new,
            DestroyBlocksObjective::new,
            DestroyCoreObjective::new,
            CommandModeObjective::new,
            FlagObjective::new
        );

        registerMarker(
            ShapeTextMarker::new,
            MinimapMarker::new,
            ShapeMarker::new,
            TextMarker::new,
            LineMarker::new,
            TextureMarker::new
        );
    }

    @SafeVarargs
    public static void registerObjective(Prov<? extends MapObjective>... providers){
        for(var prov : providers){
            allObjectiveTypes.add(prov);

            Class<? extends MapObjective> type = prov.get().getClass();
            String name = type.getSimpleName().replace("Objective", "");
            JsonIO.classTag(Strings.camelize(name), type);
            JsonIO.classTag(name, type);
        }
    }

    @SafeVarargs
    public static void registerMarker(Prov<? extends ObjectiveMarker>... providers){
        for(var prov : providers){
            allMarkerTypes.add(prov);

            Class<? extends ObjectiveMarker> type = prov.get().getClass();
            String name = type.getSimpleName().replace("Marker", "");
            allMarkerTypeNames.add(Strings.camelize(name));
            markerNameToType.put(name, prov);
            markerNameToType.put(Strings.camelize(name), prov);
            JsonIO.classTag(Strings.camelize(name), type);
            JsonIO.classTag(name, type);
        }
    }

    /** Adds all given objectives to the executor as root objectives. */
    public void add(MapObjective... objectives){
        for(var objective : objectives) flatten(objective);
    }

    /** Recursively adds the objective and its children. */
    private void flatten(MapObjective objective){
        for(var child : objective.children) flatten(child);

        objective.children.clear();
        all.add(objective);
    }

    /** Updates all objectives this executor contains. */
    public void update(){
        eachRunning(obj -> {
            for(var marker : obj.markers){
                if(!marker.wasAdded){
                    marker.wasAdded = true;
                    marker.added();
                }
            }

            //objectives cannot get completed on the client, but they do try to update for timers and such
            if(obj.update() && !net.client()){
                obj.completed = true;
                obj.done();
                for(var marker : obj.markers){
                    if(marker.wasAdded){
                        marker.removed();
                        marker.wasAdded = false;
                    }
                }
            }

            changed |= obj.changed;
            obj.changed = false;
        });
    }

    /** @return True if map rules should be synced. Reserved for {@link Vars#logic}; do not invoke directly! */
    public boolean checkChanged(){
        boolean has = changed;
        changed = false;

        return has;
    }

    /** @return Whether there are any qualified objectives at all. */
    public boolean any(){
        return all.count(MapObjective::qualified) > 0;
    }

    public void clear(){
        if(all.size > 0) changed = true;
        all.clear();
    }

    /** Iterates over all qualified in-map objectives. */
    public void eachRunning(Cons<MapObjective> cons){
        all.each(MapObjective::qualified, cons);
    }

    /** Iterates over all qualified in-map objectives, with a filter. */
    public <T extends MapObjective> void eachRunning(Boolf<? super MapObjective> pred, Cons<T> cons){
        all.each(obj -> obj.qualified() && pred.get(obj), cons);
    }

    @Override
    public Iterator<MapObjective> iterator(){
        return all.iterator();
    }

    @Override
    public void each(Cons<? super MapObjective> cons){
        all.each(cons);
    }

    /** Base abstract class for any in-map objective. */
    public static abstract class MapObjective{
        public @Nullable @Multiline String details;
        public @Unordered String[] flagsAdded = {};
        public @Unordered String[] flagsRemoved = {};
        public ObjectiveMarker[] markers = {};

        /** The parents of this objective. All parents must be done in order for this to be updated. */
        public transient Seq<MapObjective> parents = new Seq<>(2);
        /** Temporary container to store references since this class is static. Will immediately be flattened. */
        private transient final Seq<MapObjective> children = new Seq<>(2);

        /** For the objectives UI dialog. Do not modify directly! */
        public transient int editorX = -1, editorY = -1;

        /** Whether this objective has been done yet. This is internally set. */
        private boolean completed;
        /** Internal value. Do not modify! */
        private transient boolean depFinished, changed;

        /** @return True if this objective is done and should be removed from the executor. */
        public abstract boolean update();

        /** Reset internal state, if any. */
        public void reset(){}

        /** Called once after {@link #update()} returns true, before this objective is removed. */
        public void done(){
            changed();
            Call.objectiveCompleted(flagsRemoved, flagsAdded);
        }

        /** Notifies the executor that map rules should be synced. */
        protected void changed(){
            changed = true;
        }

        /** @return True if all {@link #parents} are completed, rendering this objective able to execute. */
        public final boolean dependencyFinished(){
            if(depFinished) return true;

            for(var parent : parents){
                if(!parent.isCompleted()) return false;
            }

            return depFinished = true;
        }

        /** @return True if this objective is done (practically, has been removed from the executor). */
        public final boolean isCompleted(){
            return completed;
        }

        /** @return Whether this objective should run at all. */
        public boolean qualified(){
            return !completed && dependencyFinished();
        }

        /** @return This objective, with the given child's parents added with this, for chaining operations. */
        public MapObjective child(MapObjective child){
            child.parents.add(this);
            children.add(child);
            return this;
        }

        /** @return This objective, with the given parent added to this objective's parents, for chaining operations. */
        public MapObjective parent(MapObjective parent){
            parents.add(parent);
            return this;
        }

        /** @return This objective, with the details message assigned to, for chaining operations. */
        public MapObjective details(String details){
            this.details = details;
            return this;
        }

        /** @return This objective, with the added-flags assigned to, for chaining operations. */
        public MapObjective flagsAdded(String... flagsAdded){
            this.flagsAdded = flagsAdded;
            return this;
        }

        /** @return This objective, with the removed-flags assigned to, for chaining operations. */
        public MapObjective flagsRemoved(String... flagsRemoved){
            this.flagsRemoved = flagsRemoved;
            return this;
        }

        /** @return This objective, with the markers assigned to, for chaining operations. */
        public MapObjective markers(ObjectiveMarker... markers){
            this.markers = markers;
            return this;
        }

        /** @return Basic mission display text. If null, falls back to standard text. */
        public @Nullable String text(){
            return null;
        }

        /** @return Details that appear upon click. */
        public @Nullable String details(){
            return details;
        }

        /** @return The localized type-name of this objective, defaulting to the class simple name without the "Objective" prefix. */
        public String typeName(){
            String className = getClass().getSimpleName().replace("Objective", "");
            return Core.bundle == null ? className : Core.bundle.get("objective." + className.toLowerCase() + ".name", className);
        }
    }

    /** Research a specific piece of content in the tech tree. */
    public static class ResearchObjective extends MapObjective{
        public @Researchable UnlockableContent content = Items.copper;

        public ResearchObjective(UnlockableContent content){
            this.content = content;
        }

        public ResearchObjective(){}

        @Override
        public boolean update(){
            return content.unlocked();
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.research", content.emoji(), content.localizedName);
        }
    }

    /** Produce a specific piece of content in the tech tree (essentially research with different text). */
    public static class ProduceObjective extends MapObjective{
        public @Researchable UnlockableContent content = Items.copper;

        public ProduceObjective(UnlockableContent content){
            this.content = content;
        }

        public ProduceObjective(){}

        @Override
        public boolean update(){
            return content.unlocked();
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.produce", content.emoji(), content.localizedName);
        }
    }

    /** Have a certain amount of item in your core. */
    public static class ItemObjective extends MapObjective{
        public Item item = Items.copper;
        public int amount = 1;

        public ItemObjective(Item item, int amount){
            this.item = item;
            this.amount = amount;
        }

        public ItemObjective(){}

        @Override
        public boolean update(){
            return state.rules.defaultTeam.items().has(item, amount);
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.item", state.rules.defaultTeam.items().get(item), amount, item.emoji(), item.localizedName);
        }
    }

    /** Get a certain item in your core (through a block, not manually.) */
    public static class CoreItemObjective extends MapObjective{
        public Item item = Items.copper;
        public int amount = 2;

        public CoreItemObjective(Item item, int amount){
            this.item = item;
            this.amount = amount;
        }

        public CoreItemObjective(){}

        @Override
        public boolean update(){
            return state.stats.coreItemCount.get(item) >= amount;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.coreitem", state.stats.coreItemCount.get(item), amount, item.emoji(), item.localizedName);
        }
    }

    /** Build a certain amount of a block. */
    public static class BuildCountObjective extends MapObjective{
        public @Synthetic Block block = Blocks.conveyor;
        public int count = 1;

        public BuildCountObjective(Block block, int count){
            this.block = block;
            this.count = count;
        }

        public BuildCountObjective(){}

        @Override
        public boolean update(){
            return state.stats.placedBlockCount.get(block, 0) >= count;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.build", count - state.stats.placedBlockCount.get(block, 0), block.emoji(), block.localizedName);
        }
    }

    /** Produce a certain amount of a unit. */
    public static class UnitCountObjective extends MapObjective{
        public UnitType unit = UnitTypes.dagger;
        public int count = 1;

        public UnitCountObjective(UnitType unit, int count){
            this.unit = unit;
            this.count = count;
        }

        public UnitCountObjective(){}

        @Override
        public boolean update(){
            return state.rules.defaultTeam.data().countType(unit) >= count;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.buildunit", count - state.rules.defaultTeam.data().countType(unit), unit.emoji(), unit.localizedName);
        }
    }

    /** Produce a certain amount of units. */
    public static class DestroyUnitsObjective extends MapObjective{
        public int count = 1;

        public DestroyUnitsObjective(int count){
            this.count = count;
        }

        public DestroyUnitsObjective(){}

        @Override
        public boolean update(){
            return state.stats.enemyUnitsDestroyed >= count;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.destroyunits", count - state.stats.enemyUnitsDestroyed);
        }
    }

    public static class TimerObjective extends MapObjective{
        public @Multiline String text;
        public @Second float duration = 60f * 30f;

        protected float countup;

        public TimerObjective(String text, float duration){
            this.text = text;
            this.duration = duration;
        }

        public TimerObjective(){
        }

        @Override
        public boolean update(){
            return (countup += Time.delta) >= duration;
        }

        @Override
        public void reset(){
            countup = 0f;
        }

        @Nullable
        @Override
        public String text(){
            if(text != null){
                int i = (int)((duration - countup) / 60f);
                StringBuilder timeString = new StringBuilder();

                int m = i / 60;
                int s = i % 60;
                if(m > 0){
                    timeString.append(m);
                    timeString.append(":");
                    if(s < 10){
                        timeString.append("0");
                    }
                }
                timeString.append(s);

                if(text.startsWith("@")){
                    return Core.bundle.format(text.substring(1), timeString.toString());
                }else{
                    try{
                        return Core.bundle.formatString(text, timeString.toString());
                    }catch(IllegalArgumentException e){
                        //illegal text.
                        text = "";
                    }

                }
            }

            return null;
        }
    }

    public static class DestroyBlockObjective extends MapObjective{
        public Point2 pos = new Point2();
        public Team team = Team.crux;
        public @Synthetic Block block = Blocks.router;

        public DestroyBlockObjective(Block block, int x, int y, Team team){
            this.block = block;
            this.team = team;
            this.pos.set(x, y);
        }

        public DestroyBlockObjective(){}

        @Override
        public boolean update(){
            var build = world.build(pos.x, pos.y);
            return build == null || build.team != team || build.block != block;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.destroyblock", block.emoji(), block.localizedName);
        }
    }

    public static class DestroyBlocksObjective extends MapObjective{
        public @Unordered Point2[] positions = {};
        public Team team = Team.crux;
        public @Synthetic Block block = Blocks.router;

        public DestroyBlocksObjective(Block block, Team team, Point2... positions){
            this.block = block;
            this.team = team;
            this.positions = positions;
        }

        public DestroyBlocksObjective(){}

        public int progress(){
            int count = 0;
            for(var pos : positions){
                var build = world.build(pos.x, pos.y);
                if(build == null || build.team != team || build.block != block){
                    count ++;
                }
            }
            return count;
        }

        @Override
        public boolean update(){
            return progress() >= positions.length;
        }

        @Override
        public String text(){
            return Core.bundle.format("objective.destroyblocks", progress(), positions.length, block.emoji(), block.localizedName);
        }
    }

    /** Command any unit to do anything. Always compete in headless mode. */
    public static class CommandModeObjective extends MapObjective{
        @Override
        public boolean update(){
            return headless || control.input.selectedUnits.contains(u -> u.isCommandable() && u.command().hasCommand());
        }

        @Override
        public String text(){
            return Core.bundle.get("objective.command");
        }
    }

    /** Wait until a logic flag is set. */
    public static class FlagObjective extends MapObjective{
        public String flag = "flag";
        public @Multiline String text;

        public FlagObjective(String flag, String text){
            this.flag = flag;
            this.text = text;
        }

        public FlagObjective(){}

        @Override
        public boolean update(){
            return state.rules.objectiveFlags.contains(flag);
        }

        @Override
        public String text(){
            return text != null && text.startsWith("@") ? Core.bundle.get(text.substring(1)) : text;
        }
    }

    /** Destroy all enemy core(s). */
    public static class DestroyCoreObjective extends MapObjective{
        @Override
        public boolean update(){
            return state.rules.waveTeam.cores().size == 0;
        }

        @Override
        public String text(){
            return Core.bundle.get("objective.destroycore");
        }
    }

    /** Marker used for drawing various content to indicate something along with an objective. Mostly used as UI overlay.  */
    public static abstract class ObjectiveMarker{
        /** Makes sure markers are only added once. */
        public transient boolean wasAdded;
        /** Whether to display marker on minimap instead of world. {@link MinimapMarker} ignores this value. */
        public boolean minimap = false;
        /** Whether to scale marker corresponding to player's zoom level. {@link MinimapMarker} ignores this value. */
        public boolean autoscale = false;
        /** Hides the marker, used by world processors. */
        protected boolean hidden = false;
        /** On which z-sorting layer is marker drawn. */
        protected float drawLayer = Layer.overlayUI;

        /** Called in the overlay draw layer.*/
        public void draw(){}
        /** Called in the small and large map. */
        public void drawMinimap(MinimapRenderer minimap){}
        /** Add any UI elements necessary. */
        public void added(){}
        /** Remove any UI elements, if necessary. */
        public void removed(){}
        /** Control marker with world processor code. Ignores NaN (null) values. */
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(Double.isNaN(p1)) return;
            switch(type){
                case visibility -> hidden = Mathf.equal((float)p1, 0f);
                case drawLayer -> drawLayer = (float)p1;
                case minimap -> minimap = !Mathf.equal((float)p1, 0f);
                case autoscale -> autoscale = !Mathf.equal((float)p1, 0f);
            }
        }
        public void setText(String text, boolean fetch){}

        public void setTexture(String textureName){}

        /** @return The localized type-name of this objective, defaulting to the class simple name without the "Marker" prefix. */
        public String typeName(){
            String className = getClass().getSimpleName().replace("Marker", "");
            return Core.bundle == null ? className : Core.bundle.get("marker." + className.toLowerCase() + ".name", className);
        }

        public static String fetchText(String text){
            return text.startsWith("@") ?
                //on mobile, try ${text}.mobile first for mobile-specific hints.
                mobile ? Core.bundle.get(text.substring(1) + ".mobile", Core.bundle.get(text.substring(1))) :
                Core.bundle.get(text.substring(1)) :
                text;

        }
    }

    /** Displays text above a shape. */
    public static class ShapeTextMarker extends ObjectiveMarker{
        public @Multiline String text = "frog";
        public @TilePos Vec2 pos = new Vec2();
        public float fontSize = 1f, textHeight = 7f;
        public @LabelFlag byte flags = WorldLabel.flagBackground | WorldLabel.flagOutline;

        public float radius = 6f, rotation = 0f;
        public int sides = 4;
        public Color color = Color.valueOf("ffd37f");

        // Cached localized text.
        private transient String fetchedText;

        public ShapeTextMarker(String text, float x, float y){
            this.text = text;
            this.pos.set(x, y);
        }

        public ShapeTextMarker(String text, float x, float y, float radius){
            this.text = text;
            this.pos.set(x, y);
            this.radius = radius;
        }

        public ShapeTextMarker(String text, float x, float y, float radius, float rotation){
            this.text = text;
            this.pos.set(x, y);
            this.radius = radius;
            this.rotation = rotation;
        }

        public ShapeTextMarker(String text, float x, float y, float radius, float rotation, float textHeight){
            this.text = text;
            this.pos.set(x, y);
            this.radius = radius;
            this.rotation = rotation;
            this.textHeight = textHeight;
        }

        public ShapeTextMarker(){}

        @Override
        public void draw(){
            if(hidden || minimap) return;

            //in case some idiot decides to make 9999999 sides and freeze the game
            int sides = Math.min(this.sides, 200);

            float scl = autoscale ? 4f / renderer.getDisplayScale() : 1f;

            Draw.z(drawLayer);
            Lines.stroke(3f * scl, Pal.gray);
            Lines.poly(pos.x, pos.y, sides, (radius + 1f) * scl, rotation);
            Lines.stroke(scl, color);
            Lines.poly(pos.x, pos.y, sides, (radius + 1f) * scl, rotation);
            Draw.reset();

            if(fetchedText == null){
                fetchedText = fetchText(text);
            }

            // font size cannot be 0
            if(Mathf.equal(fontSize, 0f)) return;

            WorldLabel.drawAt(fetchedText, pos.x, pos.y + radius * scl + textHeight * scl, drawLayer, flags, fontSize * scl);
        }

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden || !this.minimap) return;

            //in case some idiot decides to make 9999999 sides and freeze the game
            int sides = Math.min(this.sides, 200);

            minimap.transform(Tmp.v1.set(pos.x + 4f, pos.y + 4f));

            float rad = minimap.scale(radius, autoscale);

            Draw.z(drawLayer);
            Lines.stroke(minimap.scale(3f, autoscale), Pal.gray);
            Lines.poly(Tmp.v1.x, Tmp.v1.y, sides, rad + 1f, rotation);
            Lines.stroke(minimap.scale(1f, autoscale), color);
            Lines.poly(Tmp.v1.x, Tmp.v1.y, sides, rad + 1f, rotation);
            Draw.reset();

            if(fetchedText == null){
                fetchedText = fetchText(text);
            }

            // font size cannot be 0
            if(Mathf.equal(fontSize, 0f)) return;

            WorldLabel.drawAt(fetchedText, Tmp.v1.x, Tmp.v1.y + rad + minimap.scale(textHeight, autoscale), drawLayer, flags, minimap.scale(fontSize, autoscale));
        }

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos.x = (float)p1 * tilesize;
                    case fontSize -> fontSize = (float)p1;
                    case textHeight -> textHeight = (float)p1;
                    case labelFlags -> {
                        if(!Mathf.equal((float)p1, 0f)){
                            flags |= WorldLabel.flagBackground;
                        }else{
                            flags &= ~WorldLabel.flagBackground;
                        }
                    }
                    case radius -> radius = (float)p1;
                    case rotation -> rotation = (float)p1;
                    case color -> color.set(Tmp.c1.fromDouble(p1));
                    case shape -> sides = (int)p1;
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                switch(type){
                    case pos -> pos.y = (float)p2 * tilesize;
                    case labelFlags -> {
                        if(!Mathf.equal((float)p2, 0f)){
                            flags |= WorldLabel.flagOutline;
                        }else{
                            flags &= ~WorldLabel.flagOutline;
                        }
                    }
                    default -> super.control(type, p1, p2, p3);
                }
            }
        }

        @Override
        public void setText(String text, boolean fetch){
            this.text = text;
            if(fetch){
                fetchedText = fetchText(this.text);
            }else{
                fetchedText = this.text;
            }
        }
    }

    /** Displays a circle on the minimap. */
    public static class MinimapMarker extends ObjectiveMarker{
        public Point2 pos = new Point2();
        public float radius = 5f, stroke = 11f;
        public Color color = Color.valueOf("f25555");

        public MinimapMarker(int x, int y){
            this.pos.set(x, y);
        }

        public MinimapMarker(int x, int y, Color color){
            this.pos.set(x, y);
            this.color = color;
        }

        public MinimapMarker(int x, int y, float radius, float stroke, Color color){
            this.pos.set(x, y);
            this.stroke = stroke;
            this.radius = radius;
            this.color = color;
        }

        public MinimapMarker(){}

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden) return;

            minimap.transform(Tmp.v1.set(pos.x * tilesize, pos.y * tilesize));

            float rad = minimap.scale(radius * tilesize, autoscale);
            float fin = Interp.pow2Out.apply((Time.globalTime / 100f) % 1f);

            Draw.z(drawLayer);
            Lines.stroke(Scl.scl((1f - fin) * stroke + 0.1f), color);
            Lines.circle(Tmp.v1.x, Tmp.v1.y, rad * fin);

            Draw.reset();
        }

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos.x = (int)p1;
                    case radius -> radius = (float)p1;
                    case stroke -> stroke = (float)p1;
                    case color -> color.set(Tmp.c1.fromDouble(p1));
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                if(type == LMarkerControl.pos){
                    pos.y = (int)p2;
                }else{
                    super.control(type, p1, p2, p3);
                }
            }
        }
    }

    /** Displays a shape with an outline and color. */
    public static class ShapeMarker extends ObjectiveMarker{
        public @TilePos Vec2 pos = new Vec2();
        public float radius = 8f, rotation = 0f, stroke = 1f;
        public boolean fill = false, outline = true;
        public int sides = 4;
        public Color color = Color.valueOf("ffd37f");

        public ShapeMarker(float x, float y){
            this.pos.set(x, y);
        }

        public ShapeMarker(float x, float y, float radius, float rotation){
            this.pos.set(x, y);
            this.radius = radius;
            this.rotation = rotation;
        }

        public ShapeMarker(){}

        @Override
        public void draw(){
            if(hidden || minimap) return;

            //in case some idiot decides to make 9999999 sides and freeze the game
            int sides = Math.min(this.sides, 200);

            float scl = autoscale ? 4f / renderer.getDisplayScale() : 1f;

            Draw.z(drawLayer);
            if(!fill){
                if(outline){
                    Lines.stroke((stroke + 2f) * scl, Pal.gray);
                    Lines.poly(pos.x, pos.y, sides, (radius + 1f) * scl, rotation);
                }

                Lines.stroke(stroke * scl, color);
                Lines.poly(pos.x, pos.y, sides, (radius + 1f) * scl, rotation);
            }else{
                Draw.color(color);
                Fill.poly(pos.x, pos.y, sides, radius * scl, rotation);
            }

            Draw.reset();
        }

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden || !this.minimap) return;

            //in case some idiot decides to make 9999999 sides and freeze the game
            int sides = Math.min(this.sides, 200);

            minimap.transform(Tmp.v1.set(pos.x + 4f, pos.y + 4f));

            float rad = minimap.scale(radius, autoscale);

            Draw.z(drawLayer);
            if(!fill){
                if(outline){
                    Lines.stroke(minimap.scale(stroke + 2f, autoscale), Pal.gray);
                    Lines.poly(Tmp.v1.x, Tmp.v1.y, sides, minimap.scale(radius + 1f, autoscale), rotation);
                }

                Lines.stroke(stroke, color);
                Lines.poly(Tmp.v1.x, Tmp.v1.y, sides, minimap.scale(radius + 1f, autoscale), rotation);
            }else{
                Draw.color(color);
                Fill.poly(Tmp.v1.x, Tmp.v1.y, sides, minimap.scale(radius, autoscale), rotation);
            }

            Draw.reset();
        }

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos.x = (float)p1 * tilesize;
                    case radius -> radius = (float)p1;
                    case stroke -> stroke = (float)p1;
                    case rotation -> rotation = (float)p1;
                    case color -> color.set(Tmp.c1.fromDouble(p1));
                    case shape -> sides = (int)p1;
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                switch(type){
                    case pos -> pos.y = (float)p2 * tilesize;
                    case shape -> fill = !Mathf.equal((float)p2, 0f);
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p3)){
                if(type == LMarkerControl.shape){
                    outline = !Mathf.equal((float)p3, 0f);
                }else{
                    super.control(type, p1, p2, p3);
                }
            }
        }
    }

    /** Displays text at a location. */
    public static class TextMarker extends ObjectiveMarker{
        public @Multiline String text = "uwu";
        public @TilePos Vec2 pos = new Vec2();
        public float fontSize = 1f;
        public @LabelFlag byte flags = WorldLabel.flagBackground | WorldLabel.flagOutline;
        // Cached localized text.
        private transient String fetchedText;

        public TextMarker(String text, float x, float y, float fontSize, byte flags){
            this.text = text;
            this.fontSize = fontSize;
            this.flags = flags;
            this.pos.set(x, y);
        }

        public TextMarker(String text, float x, float y){
            this.text = text;
            this.pos.set(x, y);
        }

        public TextMarker(){}

        @Override
        public void draw(){
            // font size cannot be 0
            if(hidden || Mathf.equal(fontSize, 0f) || minimap) return;

            if(fetchedText == null){
                fetchedText = fetchText(text);
            }

            float scl = autoscale ? 4f / renderer.getDisplayScale() : 1f;

            WorldLabel.drawAt(fetchedText, pos.x, pos.y, drawLayer, flags, fontSize * scl);
        }

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden || !this.minimap) return;

            float size = minimap.scale(fontSize, autoscale);

            // font size cannot be 0
            if(Mathf.equal(fontSize, 0f)) return;

            minimap.transform(Tmp.v1.set(pos.x + 4f, pos.y + 4f));

            if(fetchedText == null){
                fetchedText = fetchText(text);
            }

            WorldLabel.drawAt(fetchedText, Tmp.v1.x, Tmp.v1.y, drawLayer, flags, size);
        }

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos.x = (float)p1 * tilesize;
                    case fontSize -> fontSize = (float)p1;
                    case labelFlags -> {
                        if(!Mathf.equal((float)p1, 0f)){
                            flags |= WorldLabel.flagBackground;
                        }else{
                            flags &= ~WorldLabel.flagBackground;
                        }
                    }
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                switch(type){
                    case pos -> pos.y = (float)p2 * tilesize;
                    case labelFlags -> {
                        if(!Mathf.equal((float)p2, 0f)){
                            flags |= WorldLabel.flagOutline;
                        }else{
                            flags &= ~WorldLabel.flagOutline;
                        }
                    }
                    default -> super.control(type, p1, p2, p3);
                }
            }
        }

        @Override
        public void setText(String text, boolean fetch){
            this.text = text;
            if(fetch){
                fetchedText = fetchText(this.text);
            }else{
                fetchedText = this.text;
            }
        }
    }

    /** Displays a line from pos1 to pos2. */
    public static class LineMarker extends ObjectiveMarker{
        public @TilePos Vec2 pos1 = new Vec2(), pos2 = new Vec2();
        public float stroke = 1f;
        public boolean outline = true;
        public Color color = Color.valueOf("ffd37f");

        public LineMarker(String text, float x1, float y1, float x2, float y2, float stroke){
            this.stroke = stroke;
            this.pos1.set(x1, y1);
            this.pos2.set(x2, y2);
        }

        public LineMarker(String text, float x1, float y1, float x2, float y2){
            this.pos1.set(x1, y1);
            this.pos2.set(x2, y2);
        }

        public LineMarker(){}

        @Override
        public void draw(){
            if(hidden || minimap) return;

            float scl = autoscale ? 4f / renderer.getDisplayScale() : 1f;

            Draw.z(drawLayer);
            if(outline){
                Lines.stroke((stroke + 2f) * scl, Pal.gray);
                Lines.line(pos1.x, pos1.y, pos2.x, pos2.y);
            }

            Lines.stroke(stroke * scl, color);
            Lines.line(pos1.x, pos1.y, pos2.x, pos2.y);
        }

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden || !this.minimap) return;

            minimap.transform(Tmp.v1.set(pos1.x + 4f, pos1.y + 4f));
            minimap.transform(Tmp.v2.set(pos2.x + 4f, pos2.y + 4f));

            Draw.z(drawLayer);
            if(outline){
                Lines.stroke(minimap.scale(stroke + 2f, autoscale), Pal.gray);
                Lines.line(Tmp.v1.x, Tmp.v1.y, Tmp.v2.x, Tmp.v2.y);
            }

            Lines.stroke(minimap.scale(stroke, autoscale), color);
            Lines.line(Tmp.v1.x, Tmp.v1.y, Tmp.v2.x, Tmp.v2.y);
        }

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos1.x = (float)p1 * tilesize;
                    case endPos -> pos2.x = (float)p1 * tilesize;
                    case stroke -> stroke = (float)p1;
                    case color -> color.set(Tmp.c1.fromDouble(p1));
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                switch(type){
                    case pos -> pos1.y = (float)p2 * tilesize;
                    case endPos -> pos2.y = (float)p2 * tilesize;
                    default -> super.control(type, p1, p2, p3);
                }
            }
        }
    }

    /** Displays a texture with specified name. */
    public static class TextureMarker extends ObjectiveMarker{
        public @TilePos Vec2 pos = new Vec2();
        public float rotation = 0f, width = 0f, height = 0f; // Zero width/height scales marker to original texture's size
        public String textureName = "";
        public Color color = Color.white.cpy();

        private transient TextureRegion fetchedRegion;

        public TextureMarker(String textureName, float x, float y){
            this.textureName = textureName;
            this.pos.set(x, y);
        }

        public TextureMarker(){}

        @Override
        public void control(LMarkerControl type, double p1, double p2, double p3){
            if(!Double.isNaN(p1)){
                switch(type){
                    case pos -> pos.x = (float)p1 * tilesize;
                    case rotation -> rotation = (float)p1;
                    case textureSize -> width = (float)p1 * tilesize;
                    case color -> color.set(Tmp.c1.fromDouble(p1));
                    default -> super.control(type, p1, p2, p3);
                }
            }

            if(!Double.isNaN(p2)){
                switch(type){
                    case pos -> pos.y = (float)p2 * tilesize;
                    case textureSize -> height = (float)p2 * tilesize;
                    default -> super.control(type, p1, p2, p3);
                }
            }
        }

        @Override
        public void draw(){
            if(hidden || textureName.isEmpty() || minimap) return;

            if(fetchedRegion == null) fetchedRegion = Core.atlas.find(textureName);

            // Zero width/height scales marker to original texture's size
            if(Mathf.equal(width, 0f)) width = fetchedRegion.width * fetchedRegion.scl() * Draw.xscl;
            if(Mathf.equal(height, 0f)) height = fetchedRegion.height * fetchedRegion.scl() * Draw.yscl;

            float scl = autoscale ? 4f / renderer.getDisplayScale() : 1f;

            Draw.z(drawLayer);
            if(fetchedRegion.found()){
                Draw.color(color);
                Draw.rect(fetchedRegion, pos.x, pos.y, width * scl, height * scl, rotation);
            }else{
                Draw.color(Color.white);
                Draw.rect("error", pos.x, pos.y, width * scl, height * scl, rotation);
            }
        }

        @Override
        public void drawMinimap(MinimapRenderer minimap){
            if(hidden || textureName.isEmpty() || !this.minimap) return;

            if(fetchedRegion == null) fetchedRegion = Core.atlas.find(textureName);

            // Zero width/height scales marker to original texture's size
            if(Mathf.equal(width, 0f)) width = fetchedRegion.width * fetchedRegion.scl() * Draw.xscl;
            if(Mathf.equal(height, 0f)) height = fetchedRegion.height * fetchedRegion.scl() * Draw.yscl;

            minimap.transform(Tmp.v1.set(pos.x + 4f, pos.y + 4f));

            Draw.z(drawLayer);
            if(fetchedRegion.found()){
                Draw.color(color);
                Draw.rect(fetchedRegion, Tmp.v1.x, Tmp.v1.y, minimap.scale(width, autoscale), minimap.scale(height, autoscale), rotation);
            }else{
                Draw.color(Color.white);
                Draw.rect("error", Tmp.v1.x, Tmp.v1.y, minimap.scale(width, autoscale), minimap.scale(height, autoscale), rotation);
            }
        }

        @Override
        public void setTexture(String textureName){
            this.textureName = textureName;
            fetchedRegion = Core.atlas.find(textureName);
        }
    }

    /** For arrays or {@link Seq}s; does not create element rearrangement buttons. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface Unordered{}

    /** For {@code byte}; treats it as a world label flag. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface LabelFlag{}

    /** For {@link UnlockableContent}; filters all un-researchable content. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface Researchable{}

    /** For {@link Block}; filters all un-buildable blocks. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface Synthetic{}

    /** For {@link String}; indicates that a text area should be used. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface Multiline{}

    /** For {@code float}; multiplies the UI input by 60. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface Second{}

    /** For {@code float} or similar data structures, such as {@link Vec2}; multiplies the UI input by {@link Vars#tilesize}. */
    @Target(FIELD)
    @Retention(RUNTIME)
    public @interface TilePos{}
}
