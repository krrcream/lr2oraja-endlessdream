package bms.player.beatoraja.modmenu;

import imgui.ImGui;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;

import java.util.Arrays;
import java.util.List;

import static bms.player.beatoraja.modmenu.ImGuiRenderer.*;

public class FreqTrainerMenu {

    public static ImBoolean FREQ_TRAINER_ENABLED = new ImBoolean(false);

    private static final float RESET_VALUE = 100f;

    // playback rate in percent, 100 = 1.00x; steps of 0.1 are supported
    private static float[] freq = new float[] {100f};

    private static List<Float> buttonVals = Arrays.asList(-10f, -5f, -1f, -0.1f, RESET_VALUE, 0.1f, 1f, 5f, 10f);

    public static void show(ImBoolean showFreqTrainer) {
        float relativeX = windowWidth * 0.47f;
        float relativeY = windowHeight * 0.06f;
        ImGui.setNextWindowPos(relativeX, relativeY, ImGuiCond.FirstUseEver);

        if(ImGui.begin("Rate Modifier", showFreqTrainer, ImGuiWindowFlags.AlwaysAutoResize)) {
            ImGui.text("Modifies the chart playback rate to be faster or");
            ImGui.text("slower by a given percent.");

            buttonVals.forEach(value -> {
                if (value == RESET_VALUE) {
                    if(ImGui.button("Reset")) {
                        freq[0] = RESET_VALUE;
                    }
                } else {
                    if(ImGui.button(formatPercent(value))) {
                        freq[0] = clamp(freq[0] + value);
                    }
                }
                ImGui.sameLine();
            });
            ImGui.newLine();
            ImGui.sliderFloat("%",
                    freq,
                    50f,
                    200f,
                    "%.1f");

            ImGui.text("Controls");
            ImGui.indent();
            ImGui.checkbox("Rate Enabled", FREQ_TRAINER_ENABLED);
            ImGui.sameLine();
            helpMarker("When enabled positive rate scores will save locally, however scores will not submit to IR and result lamp will always be NO PLAY.");

            freq[0] = clamp(freq[0]);
        }
        ImGui.end();
    }

    // snaps to 0.1 so repeated presses and slider drags stay on a clean value
    private static float clamp(float result) {
        return Math.max(50f, Math.min(200f, Math.round(result * 10f) / 10f));
    }

    private static String formatPercent(float value) {
        String text = value == (int) value ? Integer.toString((int) value) : Float.toString(value);
        return (value > 0 ? "+" : "") + text + "%";
    }

    public static boolean isFreqTrainerEnabled() {
        return FREQ_TRAINER_ENABLED.get();
    }

    public static float getFreq() {
        return freq[0];
    }

    public static boolean isFreqNegative() {
        return freq[0] < RESET_VALUE;
    }

    public static String getFreqString() {
        String rate = String.format("%.3f", (freq[0] / 100.0f));
        return "[" + rate + "x]";
    }


}
