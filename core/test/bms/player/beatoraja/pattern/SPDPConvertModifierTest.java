package bms.player.beatoraja.pattern;

import bms.model.*;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SPDPConvertModifierTest {

    /** 构造一张单 tick 的 8-lane SP 谱；每个键用 wav=spLane 标识，便于追踪落点。 */
    private static BMSModel spModel(int... spLanes) {
        BMSModel model = new BMSModel();
        model.setMode(Mode.BEAT_7K);
        model.setBpm(150.0);
        TimeLine tl = new TimeLine(0.0, 0, Mode.BEAT_7K.key);
        tl.setBPM(150.0);
        for (int spLane : spLanes) {
            tl.setNote(spLane, new NormalNote(spLane));
        }
        model.setAllTimeLine(new TimeLine[]{tl});
        return model;
    }

    private static int laneOfWav(TimeLine tl, int wav) {
        for (int lane = 0; lane < tl.getLaneCount(); lane++) {
            Note n = tl.getNote(lane);
            if (n != null && n.getWav() == wav) {
                return lane;
            }
        }
        return -1;
    }

    @Test
    void convertsBeat7kToBeat14k() {
        BMSModel model = spModel(0);
        new SPDPConvertModifier().modify(model);
        assertEquals(Mode.BEAT_14K, model.getMode());
        assertEquals(16, model.getAllTimeLines()[0].getLaneCount());
    }

    @Test
    void fullChordNotesStayDistinct() {
        // 全 7 键同 tick：随机窗口 + 撞位重选后，7 个 note 应各占一个不同 lane（无同 tick 碰撞）
        BMSModel model = spModel(0, 1, 2, 3, 4, 5, 6);
        new SPDPConvertModifier().modify(model);

        TimeLine tl = model.getAllTimeLines()[0];
        java.util.HashSet<Integer> used = new java.util.HashSet<>();
        for (int sp = 0; sp < 7; sp++) {
            int lane = laneOfWav(tl, sp);
            assertTrue(lane >= 0, "SP" + (sp + 1) + " 的 note 丢失了");
            assertTrue(used.add(lane), "两个 note 撞到同一 lane " + lane);
        }
    }

    @Test
    void placesScratchOnOneOfTwoScratchLanes() {
        BMSModel model = spModel(7);
        new SPDPConvertModifier().modify(model);
        TimeLine tl = model.getAllTimeLines()[0];
        int lane = laneOfWav(tl, 7);
        assertTrue(lane == 7 || lane == 15, "scratch should land on lane 7 or 15, got " + lane);
    }

    @Test
    void addsTitlePrefix() {
        BMSModel model = spModel(0);
        model.setTitle("Test");
        new SPDPConvertModifier().modify(model);
        assertEquals("[SPtoDP]Test", model.getTitle());
    }

    @Test
    void leavesNonBeat7kUntouched() {
        BMSModel model = new BMSModel();
        model.setMode(Mode.BEAT_14K);
        model.setTitle("DP Chart");
        TimeLine tl = new TimeLine(0.0, 0, Mode.BEAT_14K.key);
        tl.setNote(0, new NormalNote(0));
        model.setAllTimeLine(new TimeLine[]{tl});

        new SPDPConvertModifier().modify(model);

        assertEquals(Mode.BEAT_14K, model.getMode());
        assertEquals("DP Chart", model.getTitle());
        assertEquals(0, laneOfWav(tl, 0)); // note 仍在原 lane
    }

    @Test
    void placesSingleKeyNoteInItsWindow() {
        // 单个键 note 无碰撞，应落在其候选窗口内，且原 SP lane 被清空
        BMSModel model = spModel(3);
        new SPDPConvertModifier().modify(model);
        TimeLine tl = model.getAllTimeLines()[0];
        int lane = laneOfWav(tl, 3);
        assertTrue(lane == 1 || lane == 0 || lane == 8 || lane == 9,
                "SP4 应落在 {1,0,8,9} 内，实际 lane " + lane);
        assertNull(tl.getNote(3)); // 原 SP lane3 不再有 note
    }
}
