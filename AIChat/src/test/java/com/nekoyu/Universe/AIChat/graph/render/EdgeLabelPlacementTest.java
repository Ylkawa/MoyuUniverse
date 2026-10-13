package com.nekoyu.Universe.AIChat.graph.render;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import java.awt.BasicStroke;
import java.awt.Shape;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EdgeLabelPlacementTest {
    @Test void captionsNeverCoverCardsOtherCaptionsOrThePageBoundary() {
        var bounds = new Rectangle2D.Float(40, 156, 1360, 640);
        var cards = List.of(new Rectangle2D.Float(1034, 296, 288, 138), new Rectangle2D.Float(1034, 554, 288, 138));
        var route = path(1178, 434, 1178, 554);
        List<Shape> obstacles = new ArrayList<>(cards); obstacles.add(protectedRoute(route));
        List<Rectangle2D.Float> placed = new ArrayList<>();
        // Two incident edges used to paint their captions at the same position beside these cards.
        for (int i = 0; i < 12; i++) {
            var placement = EdgeLabelPlacement.beside(180, 28, route, bounds, obstacles, placed);
            if (placement == null) continue;
            var label = placement.area();
            assertTrue(bounds.contains(label));
            assertTrue(obstacles.stream().noneMatch(obstacle -> obstacle.intersects(label)));
            assertTrue(placed.stream().noneMatch(label::intersects));
            placed.add(label);
        }
        assertTrue(placed.size() >= 2, "Both captions should find distinct readable positions");
    }
    @Test void oversizedCaptionCanUseACompactIndexReferenceInstead() {
        var bounds = new Rectangle2D.Float(40, 156, 560, 200);
        var cards = List.of(new Rectangle2D.Float(40, 156, 560, 50), new Rectangle2D.Float(40, 286, 560, 70));
        var route = path(320, 206, 320, 286);
        List<Shape> obstacles = new ArrayList<>(cards); obstacles.add(protectedRoute(route));
        assertNull(EdgeLabelPlacement.beside(240, 100, route, bounds, obstacles, List.of()));
        var reference = EdgeLabelPlacement.beside(38, 22, route, bounds, obstacles, List.of());
        assertNotNull(reference); assertTrue(bounds.contains(reference.area()));
        assertTrue(obstacles.stream().noneMatch(obstacle -> obstacle.intersects(reference.area())));
    }
    @ParameterizedTest @CsvSource({"200,300,600,300", "400,180,400,500", "200,180,600,500"})
    void captionSitsBesideHorizontalVerticalAndDiagonalRoutes(float x1, float y1, float x2, float y2) {
        var route = path(x1, y1, x2, y2);
        var obstacle = protectedRoute(route);
        var bounds = new Rectangle2D.Float(40, 156, 760, 500);
        var placement = EdgeLabelPlacement.beside(100, 28, route, bounds, List.of(obstacle), List.of());
        assertNotNull(placement); assertTrue(bounds.contains(placement.area()));
        assertFalse(obstacle.intersects(placement.area()), "Caption must leave the route intact");
        assertTrue(placement.area().getCenterX() != placement.anchor().x || placement.area().getCenterY() != placement.anchor().y);
    }
    @Test void captionAvoidsOtherRoutesAndArrowheadsIncludingEdgesPaintedLater() {
        var route = path(400, 180, 400, 600);
        var crossing = path(100, 390, 720, 390);
        var arrow = new Path2D.Float(); arrow.moveTo(490, 390); arrow.lineTo(410, 350); arrow.lineTo(410, 430); arrow.closePath();
        List<Shape> obstacles = List.of(protectedRoute(route), protectedRoute(crossing), arrow);
        var placement = EdgeLabelPlacement.beside(110, 28, route, new Rectangle2D.Float(40, 156, 760, 500), obstacles, List.of());
        assertNotNull(placement);
        assertTrue(obstacles.stream().noneMatch(obstacle -> obstacle.intersects(placement.area())));
    }
    @Test void curveCaptionsAlsoClearTheWholeCurve() {
        var curve = new Path2D.Float(); curve.moveTo(280, 250); curve.curveTo(400, 150, 400, 450, 280, 350);
        var obstacle = protectedRoute(curve);
        var placement = EdgeLabelPlacement.beside(70, 28, curve, new Rectangle2D.Float(40, 156, 760, 500), List.of(obstacle), List.of());
        assertNotNull(placement); assertFalse(obstacle.intersects(placement.area()));
    }
    private static Path2D.Float path(float x1, float y1, float x2, float y2) {
        var path = new Path2D.Float(); path.moveTo(x1, y1); path.lineTo(x2, y2); return path;
    }
    private static Shape protectedRoute(Path2D route) {
        return new BasicStroke(14, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND).createStrokedShape(route);
    }
}
