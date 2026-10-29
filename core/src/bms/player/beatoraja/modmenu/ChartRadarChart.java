package bms.player.beatoraja.modmenu;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

/**
 * 七维雷达的绘制：四圈网格、七条轴线、数据多边形、轴标签与数值。
 *
 * <p>数据多边形可能凹陷，所以填充用从圆心出发的三角形扇形，而不是 addConvexPolyFilled。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarChart {

	/** 轴的满值 */
	private static final float AXIS_MAX = 200.0f;

	/** 网格圈数 */
	private static final int RING_COUNT = 4;

	private ChartRadarChart() {
	}

	/**
	 * 在窗口绘图列表上画一张雷达图。{@code values} 为 null 时只画骨架并标注 no chart data。
	 */
	public static void draw(float centerX, float centerY, float radius, float[] values, boolean showValues) {
		final ImDrawList drawList = ImGui.getWindowDrawList();
		final int gridColor = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.16f);
		final int axisColor = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.10f);
		final int fillColor = ImGui.getColorU32(0.25f, 0.62f, 1.0f, 0.35f);
		final int lineColor = ImGui.getColorU32(0.45f, 0.80f, 1.0f, 1.0f);
		final int labelColor = ImGui.getColorU32(0.92f, 0.92f, 0.95f, 1.0f);
		final int valueColor = ImGui.getColorU32(0.70f, 0.84f, 1.0f, 1.0f);

		final int axes = ChartRadarCalculator.AXIS_COUNT;
		final float[] axisX = new float[axes];
		final float[] axisY = new float[axes];
		for (int i = 0; i < axes; i++) {
			// 从正上方开始顺时针排布
			final double angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / axes;
			axisX[i] = centerX + (float) (Math.cos(angle) * radius);
			axisY[i] = centerY + (float) (Math.sin(angle) * radius);
		}

		for (int ring = 1; ring <= RING_COUNT; ring++) {
			final float scale = (float) ring / RING_COUNT;
			final ImVec2[] points = new ImVec2[axes + 1];
			for (int i = 0; i < axes; i++) {
				points[i] = new ImVec2(centerX + (axisX[i] - centerX) * scale, centerY + (axisY[i] - centerY) * scale);
			}
			points[axes] = points[0];
			drawList.addPolyline(points, points.length, gridColor, 0, 1.0f);
		}

		for (int i = 0; i < axes; i++) {
			drawList.addLine(centerX, centerY, axisX[i], axisY[i], axisColor);
		}

		drawLabels(drawList, centerX, centerY, radius, values, showValues, labelColor, valueColor);

		if (values == null) {
			final String text = "no chart data";
			final ImVec2 textSize = ImGui.calcTextSize(text);
			drawList.addText(centerX - textSize.x / 2.0f, centerY - textSize.y / 2.0f, labelColor, text);
			return;
		}

		final float[] dataX = new float[axes];
		final float[] dataY = new float[axes];
		for (int i = 0; i < axes; i++) {
			final float ratio = Math.max(0.0f, Math.min(values[i] / AXIS_MAX, 1.0f));
			dataX[i] = centerX + (axisX[i] - centerX) * ratio;
			dataY[i] = centerY + (axisY[i] - centerY) * ratio;
		}
		for (int i = 0; i < axes; i++) {
			final int next = (i + 1) % axes;
			drawList.addTriangleFilled(centerX, centerY, dataX[i], dataY[i], dataX[next], dataY[next], fillColor);
		}
		final ImVec2[] outline = new ImVec2[axes + 1];
		for (int i = 0; i < axes; i++) {
			outline[i] = new ImVec2(dataX[i], dataY[i]);
		}
		outline[axes] = outline[0];
		drawList.addPolyline(outline, outline.length, lineColor, 0, 2.0f);
	}

	/** 轴标签画在轴外侧，数值显示在标签下方 */
	private static void drawLabels(ImDrawList drawList, float centerX, float centerY, float radius, float[] values,
			boolean showValues, int labelColor, int valueColor) {
		final int axes = ChartRadarCalculator.AXIS_COUNT;
		final float fontSize = ImGui.getFontSize();
		final float labelRadius = radius + fontSize * 0.9f;
		for (int i = 0; i < axes; i++) {
			final double angle = -Math.PI / 2.0 + 2.0 * Math.PI * i / axes;
			final String label = ChartRadarCalculator.AXIS_LABELS[i];
			final ImVec2 labelSize = ImGui.calcTextSize(label);
			final float x = centerX + (float) (Math.cos(angle) * labelRadius);
			final float y = centerY + (float) (Math.sin(angle) * labelRadius);
			drawList.addText(x - labelSize.x / 2.0f, y - labelSize.y / 2.0f, labelColor, label);
			if (showValues && values != null) {
				final String value = String.valueOf(Math.round(values[i]));
				final ImVec2 valueSize = ImGui.calcTextSize(value);
				drawList.addText(x - valueSize.x / 2.0f, y + labelSize.y / 2.0f + 1.0f, valueColor, value);
			}
		}
	}
}
