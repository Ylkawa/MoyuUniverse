package com.nekoyu.Universe.AIChat.graph.render;

import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Place captions beside actual route segments, clearing every route and arrow on the page. */
final class EdgeLabelPlacement {
    private static final float CLEARANCE = 12;
    record Placement(Rectangle2D.Float area, Point2D.Float anchor) {}
    private record Segment(float x1, float y1, float x2, float y2) {
        double length() { return Point2D.distance(x1, y1, x2, y2); }
    }

    static Placement beside(float width, float height, Path2D path, Rectangle2D.Float bounds,
                             List<? extends Shape> obstacles, List<Rectangle2D.Float> labels) {
        var segments = segments(path);
        segments.sort(Comparator.comparingDouble(Segment::length).reversed());
        for (Segment segment : segments) {
            double length = segment.length();
            if (length < 4) continue;
            float nx = (float) (-(segment.y2() - segment.y1()) / length);
            float ny = (float) ((segment.x2() - segment.x1()) / length);
            float distance = Math.abs(nx) * width / 2 + Math.abs(ny) * height / 2 + CLEARANCE;
            for (float position : new float[]{.5f, .33f, .67f, .15f, .85f}) {
                float mx = segment.x1() + (segment.x2() - segment.x1()) * position;
                float my = segment.y1() + (segment.y2() - segment.y1()) * position;
                for (int side : new int[]{-1, 1}) {
                    var area = new Rectangle2D.Float(mx + side * nx * distance - width / 2,
                            my + side * ny * distance - height / 2, width, height);
                    if (!bounds.contains(area) || obstacles.stream().anyMatch(obstacle -> obstacle.intersects(area))
                            || labels.stream().anyMatch(label -> label.intersects(area.x - 4, area.y - 4, width + 8, height + 8))) continue;
                    return new Placement(area, new Point2D.Float(mx, my));
                }
            }
        }
        return null;
    }

    private static List<Segment> segments(Path2D path) {
        List<Segment> result = new ArrayList<>();
        var iterator = path.getPathIterator(null, .75);
        float[] coordinates = new float[6]; float x = 0, y = 0;
        while (!iterator.isDone()) {
            int kind = iterator.currentSegment(coordinates);
            if (kind == PathIterator.SEG_LINETO && Point2D.distance(x, y, coordinates[0], coordinates[1]) > .01) {
                Segment next = new Segment(x, y, coordinates[0], coordinates[1]);
                if (!result.isEmpty()) {
                    Segment prior = result.get(result.size() - 1);
                    float ax = prior.x2() - prior.x1(), ay = prior.y2() - prior.y1();
                    float bx = next.x2() - next.x1(), by = next.y2() - next.y1();
                    if (prior.x2() == x && prior.y2() == y && Math.abs(ax * by - ay * bx) < .01 && ax * bx + ay * by > 0) {
                        result.set(result.size() - 1, new Segment(prior.x1(), prior.y1(), next.x2(), next.y2()));
                    } else result.add(next);
                } else result.add(next);
            }
            x = coordinates[0]; y = coordinates[1]; iterator.next();
        }
        return result;
    }
}
