package com.gena.brokensignal;

import com.gena.brokensignal.EventDef.Cat;
import com.gena.brokensignal.EventDef.Rarity;
import com.gena.brokensignal.EventDef.Size;
import com.gena.brokensignal.EventCtx.Place;
import com.gena.brokensignal.EventCtx.Time;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of every event: the 38 legacy events from HorrorEvents (unchanged code, now with
 * metadata) plus the v4 content in ExtraEvents. The Director only reads this table.
 */
public final class EventCatalog {
    private static final Map<String, EventDef> DEFS = new LinkedHashMap<>();

    private EventCatalog() {}

    public static EventDef get(String id) {
        return DEFS.get(id);
    }

    public static Collection<EventDef> all() {
        return Collections.unmodifiableCollection(DEFS.values());
    }

    public static Set<String> ids() {
        return Collections.unmodifiableSet(DEFS.keySet());
    }

    private static EventDef add(EventDef d) {
        if (DEFS.put(d.id, d) != null) {
            throw new IllegalStateException("duplicate event id " + d.id);
        }
        return d;
    }

    private static EventDef old(String id, Cat cat) {
        return add(new EventDef(id, cat, (p, c, f) -> HorrorEvents.run(p, id, f)));
    }

    private static EventDef ev(String id, Cat cat, EventDef.Action a) {
        return add(new EventDef(id, cat, a));
    }

    private static EventDef pc(String id, EventDef.Action a) {
        return add(new EventDef(id, Cat.COMPUTER, a)).when(EventCtx::hasPc).phase(2);
    }

    static {
        // ---------------------------------------------------------- legacy (v1-v3), kept as-is
        old("whisper", Cat.SOUND).phase(2).weight(6).cooldown(25);
        old("footsteps", Cat.SOUND).phase(1).weight(10).cooldown(12).at(Place.HOME, Place.CAVE, Place.BASEMENT);
        old("knock", Cat.SOUND).phase(2).weight(8).cooldown(20).at(Place.HOME, Place.PC_ROOM);
        old("drone", Cat.SOUND).phase(3).weight(5).cooldown(30).at(Place.CAVE);
        old("cave", Cat.SOUND).phase(1).weight(8).cooldown(15).at(Place.CAVE, Place.OPEN);
        old("creeper", Cat.SOUND).phase(2).weight(4).cooldown(40);
        old("mining", Cat.SOUND).phase(2).weight(7).cooldown(20).only(Place.CAVE, Place.BASEMENT);
        old("door", Cat.WORLD).phase(2).weight(8).cooldown(20).edits().at(Place.HOME, Place.VILLAGE);
        old("torches", Cat.WORLD).phase(3).weight(6).cooldown(25).edits().at(Place.CAVE, Place.HOME);
        old("tunnel", Cat.WORLD).size(Size.MEDIUM).phase(4).weight(4).cooldown(60).edits().at(Place.CAVE);
        old("leaves", Cat.WORLD).phase(3).weight(5).cooldown(40).edits().only(Place.FOREST);
        old("pillar", Cat.WORLD).size(Size.MEDIUM).phase(4).weight(4).cooldown(60).edits().at(Place.OPEN, Place.FOREST);
        old("sign", Cat.WORLD).size(Size.MEDIUM).phase(4).weight(4).cooldown(45).edits();
        old("gift", Cat.WORLD).phase(3).weight(4).cooldown(50).edits().at(Place.HOME);
        old("house", Cat.WORLD).size(Size.MAJOR).rarity(Rarity.RARE).phase(5).weight(3).once().edits().quiet(15);
        old("chest", Cat.WORLD).phase(3).weight(5).cooldown(40).at(Place.HOME);
        old("rearrange", Cat.WORLD).size(Size.MEDIUM).phase(5).weight(3).cooldown(80).edits().at(Place.HOME);
        old("lag", Cat.PLAYER).phase(2).weight(6).cooldown(30);
        old("shuffle", Cat.PLAYER).phase(4).weight(3).cooldown(60);
        old("wake", Cat.PLAYER).manual();
        old("stare", Cat.PLAYER).phase(3).weight(5).cooldown(30);
        old("darkness", Cat.PLAYER).size(Size.MEDIUM).phase(5).weight(3).cooldown(60).when(EventCtx::night);
        old("chat", Cat.MESSAGE).phase(4).weight(4).cooldown(40);
        old("join", Cat.MESSAGE).phase(3).weight(4).cooldown(60);
        old("mimic", Cat.MESSAGE).phase(5).weight(3).cooldown(60).manual();
        old("deathmsg", Cat.MESSAGE).phase(5).weight(3).cooldown(70);
        old("advancement", Cat.MESSAGE).phase(4).weight(3).cooldown(80);
        old("watcher", Cat.FIGURE).size(Size.MEDIUM).phase(3).weight(6).cooldown(30).at(Place.FOREST, Place.OPEN, Place.CAVE);
        old("behind", Cat.FIGURE).size(Size.MEDIUM).phase(5).weight(3).cooldown(50);
        old("turn", Cat.FIGURE).size(Size.MEDIUM).phase(5).weight(3).cooldown(60);
        old("chase", Cat.FIGURE).size(Size.MAJOR).rarity(Rarity.RARE).phase(7).weight(2).cooldown(150).danger(4).quiet(20).when(EventCtx::night);
        old("title", Cat.META).phase(5).weight(3).cooldown(70).visual();
        old("silence", Cat.META).phase(3).weight(4).cooldown(40).visual();
        old("pause", Cat.META).size(Size.MEDIUM).rarity(Rarity.RARE).phase(6).weight(2).once().visual();
        old("lost", Cat.META).size(Size.MAJOR).rarity(Rarity.RARE).phase(6).weight(2).once().visual().quiet(10);
        old("static", Cat.META).size(Size.MEDIUM).rarity(Rarity.UNCOMMON).phase(5).weight(2).once().visual();
        old("screenshot", Cat.META).manual();
        old("user", Cat.META).manual();
        old("note", Cat.META).manual();

        // ---------------------------------------------------------- small
        ev("distant_chest", Cat.SOUND, ExtraEvents::distantChest).phase(1).weight(8).cooldown(25).at(Place.HOME, Place.VILLAGE);
        ev("glass", Cat.SOUND, ExtraEvents::glass).phase(2).weight(5).cooldown(40).at(Place.HOME, Place.VILLAGE);
        ev("step_echo", Cat.SOUND, ExtraEvents::stepEcho).phase(1).weight(8).cooldown(20);
        ev("eating", Cat.SOUND, ExtraEvents::eating).phase(3).weight(4).cooldown(50).at(Place.HOME, Place.PC_ROOM);
        ev("far_door", Cat.SOUND, ExtraEvents::farDoor).phase(2).weight(7).cooldown(25).at(Place.HOME, Place.VILLAGE, Place.HOUSE_SITE);
        ev("bell", Cat.SOUND, ExtraEvents::bell).phase(3).weight(3).cooldown(90).when(EventCtx::night).at(Place.OPEN, Place.VILLAGE);
        ev("anvil", Cat.SOUND, ExtraEvents::anvil).phase(2).weight(4).cooldown(60);
        ev("frame", Cat.SOUND, ExtraEvents::frame).phase(1).weight(6).cooldown(30).at(Place.HOME, Place.PC_ROOM);
        ev("pickup", Cat.SOUND, ExtraEvents::pickup).phase(1).weight(6).cooldown(30);
        ev("notes", Cat.SOUND, ExtraEvents::notes).phase(3).weight(3).cooldown(70).when(EventCtx::night);
        ev("drip", Cat.SOUND, ExtraEvents::drip).phase(1).weight(6).cooldown(25).at(Place.CAVE, Place.BASEMENT, Place.HOME);
        ev("scrape", Cat.SOUND, ExtraEvents::scrape).phase(3).weight(5).cooldown(30).only(Place.CAVE, Place.BASEMENT);
        ev("bed_creak", Cat.SOUND, ExtraEvents::bedCreak).phase(2).weight(5).cooldown(40).only(Place.HOME);
        ev("lever", Cat.SOUND, ExtraEvents::lever).phase(2).weight(4).cooldown(40);
        ev("ladder", Cat.SOUND, ExtraEvents::ladder).phase(3).weight(4).cooldown(40).only(Place.HOME, Place.BASEMENT);
        ev("button", Cat.SOUND, ExtraEvents::button).phase(2).weight(4).cooldown(40);
        ev("campfire_sound", Cat.SOUND, ExtraEvents::campfireSound).phase(2).weight(5).cooldown(40).only(Place.FOREST, Place.OPEN);
        ev("xp", Cat.SOUND, ExtraEvents::xp).phase(1).weight(3).cooldown(60);
        ev("bucket", Cat.SOUND, ExtraEvents::bucket).phase(2).weight(4).cooldown(45);
        ev("furnace_sound", Cat.SOUND, ExtraEvents::furnaceSound).phase(2).weight(4).cooldown(40).only(Place.HOME, Place.PC_ROOM);
        ev("flicker", Cat.META, ExtraEvents::flicker).phase(2).weight(5).cooldown(30).visual();
        ev("hud_gone", Cat.META, ExtraEvents::hudGone).phase(4).weight(3).cooldown(60).visual();
        ev("chunk_err", Cat.META, ExtraEvents::chunkErr).phase(3).weight(3).cooldown(90).visual();
        ev("fake_saving", Cat.META, ExtraEvents::fakeSaving).phase(3).weight(3).cooldown(80).visual();
        ev("wrong_name", Cat.META, ExtraEvents::wrongName).phase(4).weight(3).cooldown(60).visual();
        ev("fog", Cat.META, ExtraEvents::fog).phase(4).weight(3).cooldown(70).visual().at(Place.FOREST, Place.OPEN);
        ev("login_turn", Cat.PLAYER, ExtraEvents::loginTurn).phase(4).manual();
        ev("torch_extra", Cat.WORLD, ExtraEvents::torchExtra).phase(3).weight(4).cooldown(60).edits().at(Place.HOME);
        ev("flower", Cat.WORLD, ExtraEvents::flower).phase(2).weight(5).cooldown(40).edits().at(Place.OPEN, Place.FOREST);
        ev("hotbar_shift", Cat.PLAYER, ExtraEvents::hotbarShift).phase(3).weight(4).cooldown(50);
        ev("mob_stare", Cat.PLAYER, ExtraEvents::mobStare).phase(2).weight(6).cooldown(30).at(Place.OPEN, Place.VILLAGE);

        // ---------------------------------------------------------- medium
        ev("animals_face", Cat.WORLD, ExtraEvents::animalsFace).size(Size.MEDIUM).phase(4).weight(4).cooldown(60).at(Place.OPEN);
        ev("village_stare", Cat.WORLD, ExtraEvents::villageStare).size(Size.MEDIUM).phase(5).weight(4).cooldown(80).only(Place.VILLAGE);
        ev("pets", Cat.WORLD, ExtraEvents::pets).size(Size.MEDIUM).phase(4).weight(3).cooldown(80);
        ev("time_skip", Cat.WORLD, ExtraEvents::timeSkip).size(Size.MEDIUM).phase(5).weight(2).cooldown(120);
        ev("home_changed", Cat.WORLD, ExtraEvents::homeChanged).size(Size.MEDIUM).phase(3).weight(4).cooldown(60).edits()
                .when(c -> !c.at(Place.HOME)).then("pc_situation", 2, 6, 0.3F);
        ev("furnace_lit", Cat.WORLD, ExtraEvents::furnaceLit).size(Size.MEDIUM).phase(3).weight(4).cooldown(60);
        ev("trapdoor", Cat.WORLD, ExtraEvents::trapdoor).size(Size.MEDIUM).phase(3).weight(4).cooldown(50);
        ev("sign_rewrite", Cat.WORLD, ExtraEvents::signRewrite).size(Size.MEDIUM).phase(5).weight(3).cooldown(90);
        ev("crops_row", Cat.WORLD, ExtraEvents::cropsRow).size(Size.MEDIUM).phase(4).weight(3).cooldown(90).edits();
        ev("ghost_block", Cat.WORLD, ExtraEvents::ghostBlock).size(Size.MEDIUM).phase(3).weight(4).cooldown(40);
        ev("mirror_block", Cat.WORLD, ExtraEvents::mirrorBlock).size(Size.MEDIUM).phase(5).weight(3).cooldown(70).edits();
        ev("path_trail", Cat.WORLD, ExtraEvents::pathTrail).size(Size.MEDIUM).phase(5).weight(3).cooldown(90).edits().only(Place.OPEN, Place.FOREST);
        ev("follow_steps", Cat.SOUND, ExtraEvents::followSteps).size(Size.MEDIUM).phase(4).weight(4).cooldown(50);
        ev("favourite_spot", Cat.WORLD, ExtraEvents::favouriteSpot).size(Size.MEDIUM).phase(5).weight(3).cooldown(120).edits()
                .then("pc_notes_line", 5, 15, 0.5F);
        ev("still_watch", Cat.FIGURE, ExtraEvents::stillWatch).size(Size.MEDIUM).phase(4).priority(2).manual();
        ev("homecoming", Cat.WORLD, ExtraEvents::homecoming).size(Size.MEDIUM).phase(3).priority(2).manual();
        ev("after_sleep", Cat.WORLD, ExtraEvents::afterSleep).size(Size.MEDIUM).phase(3).priority(2).manual();
        ev("loading_terrain", Cat.META, ExtraEvents::loadingTerrain).size(Size.MEDIUM).phase(5).weight(2).cooldown(120).visual();
        ev("dark_pickaxe", Cat.SOUND, ExtraEvents::scrape).size(Size.MEDIUM).phase(4).weight(3).cooldown(60).only(Place.CAVE)
                .when(EventCtx::dark).then("cave_call", 3, 8, 0.25F);
        ev("chest_note", Cat.WORLD, ExtraEvents::chestTorch).size(Size.MEDIUM).phase(4).weight(3).cooldown(90).at(Place.HOME);

        // ---------------------------------------------------------- major
        ev("blackout", Cat.WORLD, ExtraEvents::blackout).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(6).weight(3).cooldown(150).quiet(10)
                .when(EventCtx::night).at(Place.HOME, Place.CAVE);
        ev("storm_figure", Cat.FIGURE, ExtraEvents::stormFigure).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(5).weight(4).cooldown(120)
                .when(c -> c.thunder).quiet(8);
        ev("long_night", Cat.WORLD, ExtraEvents::longNight).size(Size.MAJOR).rarity(Rarity.RARE).phase(7).weight(2).cooldown(300)
                .when(c -> c.time == Time.MIDNIGHT).quiet(10);
        ev("wrong_world", Cat.META, ExtraEvents::wrongWorld).size(Size.MAJOR).rarity(Rarity.RARE).phase(6).weight(2).cooldown(240).visual().quiet(10);
        ev("visitor", Cat.WORLD, ExtraEvents::visitor).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(6).weight(3).cooldown(180)
                .when(EventCtx::night).only(Place.HOME).quiet(12).then("pc_unknown_msg", 3, 10, 0.5F);
        ev("meet", Cat.FIGURE, ExtraEvents::meet).size(Size.MAJOR).rarity(Rarity.RARE).phase(6).weight(4).cooldown(200)
                .when(c -> c.st.pos("death") != null).quiet(10);
        ev("cave_call", Cat.WORLD, ExtraEvents::caveCall).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(5).weight(3).cooldown(120).only(Place.CAVE).quiet(8);
        ev("chest_torch", Cat.WORLD, ExtraEvents::chestTorch).size(Size.MAJOR).rarity(Rarity.RARE).phase(6).weight(2).cooldown(240).at(Place.HOME);
        ev("light_far", Cat.WORLD, ExtraEvents::lightFar).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(5).weight(3).cooldown(150).edits()
                .when(EventCtx::night).only(Place.OPEN, Place.FOREST).quiet(8);
        ev("house_visit", Cat.WORLD, ExtraEvents::visitor).size(Size.MAJOR).rarity(Rarity.RARE).phase(7).weight(3).cooldown(240).only(Place.HOUSE_SITE).quiet(12);
        ev("deep_quiet", Cat.META, (p, c, f) -> ExtraEvents.client(p, "silence", "3600")).size(Size.MAJOR).rarity(Rarity.UNCOMMON).phase(6)
                .weight(3).cooldown(180).visual().quiet(15).then("visitor", 2, 4, 0.3F);

        // ---------------------------------------------------------- computer
        pc("pc_file_new", ExtraEvents::pcFileNew).weight(8).cooldown(20);
        pc("pc_rename", ExtraEvents::pcRename).phase(3).weight(5).cooldown(30);
        pc("pc_drift", ExtraEvents::pcDrift).phase(3).weight(5).cooldown(30);
        pc("pc_bad_date", ExtraEvents::pcBadDate).phase(3).weight(4).cooldown(40);
        pc("pc_new_folder", ExtraEvents::pcNewFolder).weight(4).cooldown(40).then("pc_fill_folder", 10, 30, 0.7F);
        pc("pc_fill_folder", ExtraEvents::pcFillFolder).phase(3).manual();
        pc("pc_clock", ExtraEvents::pcClock).phase(3).weight(4).cooldown(40);
        pc("pc_autotype", ExtraEvents::pcAutotype).phase(4).weight(4).cooldown(30).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_flicker", ExtraEvents::pcFlicker).weight(5).cooldown(15).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_freeze", ExtraEvents::pcFreeze).phase(3).weight(4).cooldown(25).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_shuffle", ExtraEvents::pcShuffle).phase(3).weight(4).cooldown(30).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_ui_wrong", ExtraEvents::pcUiWrong).phase(5).weight(3).cooldown(40).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_cursor", ExtraEvents::pcCursor).phase(4).weight(4).cooldown(30).when(c -> com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_unknown_msg", ExtraEvents::pcUnknownMsg).phase(4).weight(4).cooldown(50);
        pc("pc_situation", ExtraEvents::pcSituation).phase(4).weight(3).cooldown(60);
        pc("pc_ghost_cmd", ExtraEvents::pcGhostCmd).phase(3).weight(4).cooldown(40);
        pc("pc_night_file", ExtraEvents::pcNightFile).phase(4).weight(3).once().when(EventCtx::night);
        pc("pc_vanish", ExtraEvents::pcVanish).phase(3).weight(4).cooldown(60);
        pc("pc_on", ExtraEvents::pcOn).phase(4).weight(3).cooldown(90).when(c -> c.pc != null && !com.gena.brokensignal.pc.ComputerService.isOpen(c.p)).when(EventCtx::night);
        pc("pc_toast", ExtraEvents::pcToast).phase(5).weight(2).cooldown(90).visual().when(c -> !com.gena.brokensignal.pc.ComputerService.isOpen(c.p));
        pc("pc_listen_back", ExtraEvents::pcListenBack).manual();
        pc("pc_restore", ExtraEvents::pcRestore).manual();
        pc("pc_notes_answer", ExtraEvents::pcNotesAnswer).manual();
        pc("pc_notes_line", ExtraEvents::pcNotesLine).phase(5).manual();

        // ---------------------------------------------------------- v5: mimics (MIMIC FIRST, HORROR SECOND)
        ev("mimic_friend", Cat.FIGURE, (p, c, f) -> com.gena.brokensignal.mimic.MimicDirector.startFriend(p, f))
                .phase(2).weight(4).cooldown(70).rarity(Rarity.UNCOMMON).size(Size.MAJOR)
                .when(c -> Config.MIMIC_ENABLED.get() && c.time != Time.MIDNIGHT);
        ev("mimic_worker", Cat.FIGURE, (p, c, f) -> com.gena.brokensignal.mimic.MimicDirector.startWorker(p, f))
                .phase(1).weight(5).cooldown(45).at(Place.FOREST, Place.OPEN, Place.CAVE)
                .when(c -> Config.MIMIC_ENABLED.get());
        ev("mimic_self", Cat.FIGURE, (p, c, f) -> com.gena.brokensignal.mimic.MimicDirector.startSelf(p, f))
                .phase(4).weight(3).cooldown(120).rarity(Rarity.RARE).size(Size.MAJOR)
                .when(c -> Config.MIMIC_ENABLED.get() && c.st.count("mimic_visits") >= 2);
        ev("pet_turn", Cat.FIGURE, com.gena.brokensignal.mimic.Scenes::petTurn)
                .phase(3).weight(3).cooldown(90).rarity(Rarity.RARE).when(c -> Config.MIMIC_ENABLED.get());
        ev("herd_sync", Cat.FIGURE, com.gena.brokensignal.mimic.Scenes::herdSync)
                .phase(2).weight(5).cooldown(40).at(Place.OPEN, Place.HOME, Place.VILLAGE);
        ev("watches_house", Cat.FIGURE, com.gena.brokensignal.mimic.Scenes::watchesHouse)
                .phase(2).weight(4).cooldown(60).when(c -> c.time == Time.DUSK || c.time == Time.DAY);
        ev("villager_extra", Cat.FIGURE, com.gena.brokensignal.mimic.Scenes::villagerExtra)
                .phase(3).weight(3).cooldown(90).at(Place.VILLAGE).rarity(Rarity.UNCOMMON).when(c -> Config.MIMIC_ENABLED.get());
        ev("villager_knows", Cat.FIGURE, com.gena.brokensignal.mimic.Scenes::villagerKnows)
                .phase(4).weight(3).cooldown(80).at(Place.VILLAGE);
        // consequences: only scheduled by something that happened before, never random
        ev("mimic_echo", Cat.WORLD, com.gena.brokensignal.ext.Echoes::mimicEcho).manual().edits();
        ev("while_away", Cat.WORLD, com.gena.brokensignal.ext.Echoes::whileAway).manual();
        ev("closed_door", Cat.WORLD, com.gena.brokensignal.ext.Echoes::closedDoor).manual();
        ev("typed_back", Cat.WORLD, com.gena.brokensignal.ext.Echoes::typedBack).manual().edits();
        ev("file_gone", Cat.WORLD, com.gena.brokensignal.ext.Echoes::fileGone).manual();

        // ---------------------------------------------------------- secrets (never explained in game)
        ev("s_self_join", Cat.SECRET, ExtraEvents::sSelfJoin).rarity(Rarity.SECRET).phase(6).weight(2).once();
        ev("s_revisit", Cat.SECRET, ExtraEvents::sRevisit).rarity(Rarity.SECRET).phase(4).weight(30).once().edits();
        ev("s_night_owl", Cat.SECRET, ExtraEvents::sNightOwl).rarity(Rarity.SECRET).phase(4).weight(30).once()
                .when(EventCtx::hasPc).when(c -> c.st.count("min_night") > 240 && c.st.count("min_night") > c.st.count("min_day"));
        ev("s_beside", Cat.SECRET, ExtraEvents::sBeside).rarity(Rarity.SECRET).phase(7).weight(5).once().quiet(20)
                .when(c -> c.time == Time.MIDNIGHT && c.st.flag("was_still"));
        ev("s_all_chains", Cat.SECRET, ExtraEvents::sAllChains).rarity(Rarity.SECRET).phase(6).weight(40).once()
                .when(EventCtx::hasPc).when(c -> c.st.chainsFinished() >= 7);
        ev("s_first_place", Cat.SECRET, ExtraEvents::sFirstPlace).rarity(Rarity.SECRET).phase(8).weight(10).once().edits()
                .when(c -> c.underground && c.p.getBlockY() < -40);
    }
}
