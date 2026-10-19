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
    void mapsSpKeysToPrimaryLanes() {
        BMSModel model = spModel(0, 1, 2, 3, 4, 5, 6);
        new SPDPConvertModifier().modify(model);

        TimeLine tl = model.getAllTimeLines()[0];
        // SP1→1P7(lane6) SP2→1P5(lane4) SP3→1P3(lane2) SP4→1P1(lane0)
        // SP5→2P2(lane9) SP6→2P4(lane11) SP7→2P6(lane13)
        assertEquals(6, laneOfWav(tl, 0));
        assertEquals(4, laneOfWav(tl, 1));
        assertEquals(2, laneOfWav(tl, 2));
        assertEquals(0, laneOfWav(tl, 3));
        assertEquals(9, laneOfWav(tl, 4));
        assertEquals(11, laneOfWav(tl, 5));
        assertEquals(13, laneOfWav(tl, 6));
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
    void leavesSingleLaneKeyNoteOnOriginalPrimary() {
        // 单个键 note 无碰撞，应落到主位，且原 SP lane 被清空
        BMSModel model = spModel(3);
        new SPDPConvertModifier().modify(model);
        TimeLine tl = model.getAllTimeLines()[0];
        assertEquals(0, laneOfWav(tl, 3)); // SP4 主位 1P1(lane0)
        assertNull(tl.getNote(3));          // 原 SP lane3 不再有 note
    }
}
