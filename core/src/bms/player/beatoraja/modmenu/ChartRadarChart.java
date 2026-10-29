package bms.player.beatoraja.modmenu;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImVec2;

/**
 * 谱面雷达的绘制：四圈网格、六条轴线、数据多边形、轴标签与数值，样式对齐 IIDX 的 NOTES RADAR。
 *
 * <p>数据多边形可能凹陷，所以填充用从圆心出发的三角形扇形，而不是 addConvexPolyFilled。</p>
 *
 * <p>ImGui 没有文字描边，这里用「黑色在周围 8 个方向各画一次再叠本色」的假描边。
 * 半径由标签实际尺寸反算，保证文字不会越出窗口边缘。</p>
 *
 * @author endlessdream
 */
public final class ChartRadarChart {

	/** 轴的满值 */
	private static final float AXIS_MAX = 200.0f;

	/** 网格圈数，最外圈是白色粗框，里面三圈是红色细框 */
	private static final int RING_COUNT = 4;

	/** 六轴配色，顺序与 {@link ChartRadarCalculator#AXIS_LABELS} 一致（品红 / 橙 / 红 / 蓝 / 紫 / 绿） */
	private static final float[][] AXIS_COLORS = {
			{ 1.00f, 0.42f, 0.83f },
			{ 1.00f, 0.62f, 0.15f },
			{ 1.00f, 0.25f, 0.22f },
			{ 0.35f, 0.62f, 1.00f },
			{ 0.72f, 0.45f, 1.00f },
			{ 0.45f, 0.95f, 0.35f },
	};

	private ChartRadarChart() {
	}

	/**
	 * 在窗口绘图列表上画一张雷达图。{@code values} 为 null 时只画骨架并标注 no chart data。
	 *
	 * @param availableSize 可用于画图的边长（窗口边长），半径会按标签尺寸自动内缩
	 */
	public static void draw(float centerX, float centerY, float availableSize, float[] values, boolean showValues) {
		final ImDrawList drawList = ImGui.getWindowDrawList();
		final int outlineColor = ImGui.getColorU32(0.0f, 0.0f, 0.0f, 0.95f);
		final int outerRingColor = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.95f);
		final int innerRingColor = ImGui.getColorU32(0.82f, 0.12f, 0.14f, 0.85f);
		final int axisColor = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.20f);
		final int fillColor = ImGui.getColorU32(0.55f, 0.04f, 0.07f, 0.60f);
		final int lineColor = ImGui.getColorU32(0.95f, 0.18f, 0.18f, 1.0f);
		final int starColor = ImGui.getColorU32(1.0f, 0.82f, 0.20f, 1.0f);
		final int valueColor = ImGui.getColorU32(0.88f, 0.88f, 0.92f, 1.0f);

		final int axes = ChartRadarCalculator.AXIS_COUNT;
		final float fontSize = ImGui.getFontSize();
		final int highestAxis = highestAxis(values);
		final boolean drawValues = showValues && values != null;

		// 先按标签（以及数值、星标）的尺寸反算半径，保证文字全部落在窗口内
		final float half = availableSize / 2.0f;
		float radius = half - 4.0f;
		for (int i = 0; i < axes; i++) {
			final double angle = angleOf(i, axes);
			final float cos = (float) Math.abs(Math.cos(angle));
			final float sin = (float) Math.abs(Math.sin(angle));
			final float starReserve = i == highestAxis ? fontSize : 0.0f;
			final float halfWidth = ImGui.calcTextSize(ChartRadarCalculator.AXIS_LABELS[i]).x / 2.0f + starReserve
					+ fontSize * 0.4f + 4.0f;
			final float halfHeight = (fontSize + (drawValues ? fontSize : 0.0f)) / 2.0f + 4.0f;
			if (cos > 0.01f) {
				radius = Math.min(radius, (half - halfWidth) / cos);
			}
			if (sin > 0.01f) {
				radius = Math.min(radius, (half - halfHeight) / sin);
			}
		}
		radius = Math.max(6.0f, radius);
		final float labelRadius = radius + fontSize * 0.35f;

		final float[] axisX = new float[axes];
		final float[] axisY = new float[axes];
		for (int i = 0; i < axes; i++) {
			final double angle = angleOf(i, axes);
			axisX[i] = centerX + (float) (Math.cos(angle) * radius);
			axisY[i] = centerY + (float) (Math.sin(angle) * radius);
		}

		for (int ring = 1; ring <= RING_COUNT; ring++) {
			final boolean outer = ring == RING_COUNT;
			final float scale = (float) ring / RING_COUNT;
			final ImVec2[] points = new ImVec2[axes + 1];
			for (int i = 0; i < axes; i++) {
				points[i] = new ImVec2(centerX + (axisX[i] - centerX) * scale, centerY + (axisY[i] - centerY) * scale);
			}
			points[axes] = points[0];
			drawList.addPolyline(points, points.length, outer ? outerRingColor : innerRingColor, 0,
					outer ? 2.0f : 1.0f);
		}

		for (int i = 0; i < axes; i++) {
			drawList.addLine(centerX, centerY, axisX[i], axisY[i], axisColor);
		}

		drawLabels(drawList, centerX, centerY, labelRadius, values, drawValues, outlineColor, starColor, valueColor,
				highestAxis);

		if (values == null) {
			final String text = "no chart data";
			final ImVec2 textSize = ImGui.calcTextSize(text);
			final float textX = centerX - textSize.x / 2.0f;
			final float textY = centerY - textSize.y / 2.0f;
			drawOutline(drawList, textX, textY, outlineColor, text);
			drawList.addText(textX, textY, valueColor, text);
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

	/** 轴标签画在轴外侧并用轴色，最高的一轴左侧加金色星标，数值显示在标签下方 */
	private static void drawLabels(ImDrawList drawList, float centerX, float centerY, float labelRadius,
			float[] values, boolean drawValues, int outlineColor, int starColor, int valueColor, int highestAxis) {
		final int axes = ChartRadarCalculator.AXIS_COUNT;
		for (int i = 0; i < axes; i++) {
			final double angle = angleOf(i, axes);
			final String label = ChartRadarCalculator.AXIS_LABELS[i];
			final ImVec2 labelSize = ImGui.calcTextSize(label);
			final float x = centerX + (float) (Math.cos(angle) * labelRadius);
			final float y = centerY + (float) (Math.sin(angle) * labelRadius);
			final float textX = x - labelSize.x / 2.0f;
			final float textY = y - labelSize.y / 2.0f;

			final int axisColor = ImGui.getColorU32(AXIS_COLORS[i][0], AXIS_COLORS[i][1], AXIS_COLORS[i][2], 1.0f);
			drawOutline(drawList, textX, textY, outlineColor, label);
			drawList.addText(textX, textY, axisColor, label);

			if (i == highestAxis) {
				final ImVec2 starSize = ImGui.calcTextSize(FontAwesomeIcons.Star);
				final float starX = textX - starSize.x - 3.0f;
				final float starY = textY + (labelSize.y - starSize.y) / 2.0f;
				drawOutline(drawList, starX, starY, outlineColor, FontAwesomeIcons.Star);
				drawList.addText(starX, starY, starColor, FontAwesomeIcons.Star);
			}

			if (drawValues) {
				final String value = String.valueOf(Math.round(values[i]));
				final ImVec2 valueSize = ImGui.calcTextSize(value);
				final float valueX = x - valueSize.x / 2.0f;
				final float valueY = textY + labelSize.y + 1.0f;
				drawOutline(drawList, valueX, valueY, outlineColor, value);
				drawList.addText(valueX, valueY, valueColor, value);
			}
		}
	}

	/** 从正上方开始顺时针的角度 */
	private static double angleOf(int index, int axes) {
		return -Math.PI / 2.0 + 2.0 * Math.PI * index / axes;
	}

	/** 假描边：黑色在周围 8 个方向各画一次 */
	private static void drawOutline(ImDrawList drawList, float x, float y, int color, String text) {
		for (int dx = -1; dx <= 1; dx++) {
			for (int dy = -1; dy <= 1; dy++) {
				if (dx == 0 && dy == 0) {
					continue;
				}
				drawList.addText(x + dx, y + dy, color, text);
			}
		}
	}

	/** 数值最高的一轴；没有数据时返回 -1 */
	private static int highestAxis(float[] values) {
		if (values == null) {
			return -1;
		}
		int highest = -1;
		float best = -1.0f;
		for (int i = 0; i < values.length; i++) {
			if (values[i] > best) {
				best = values[i];
				highest = i;
			}
		}
		return highest;
	}
}
