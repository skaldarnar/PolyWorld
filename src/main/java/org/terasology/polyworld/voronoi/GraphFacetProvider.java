/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.terasology.polyworld.voronoi;

import java.math.RoundingMode;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.Component;
import org.terasology.math.Rect2i;
import org.terasology.math.delaunay.Voronoi;
import org.terasology.math.geom.Rect2f;
import org.terasology.math.geom.Vector2f;
import org.terasology.polyworld.TriangleLookup;
import org.terasology.polyworld.rp.WorldRegionFacet;
import org.terasology.polyworld.rp.RegionType;
import org.terasology.polyworld.rp.WorldRegion;
import org.terasology.rendering.nui.properties.Range;
import org.terasology.utilities.random.FastRandom;
import org.terasology.utilities.random.Random;
import org.terasology.world.generation.Border3D;
import org.terasology.world.generation.ConfigurableFacetProvider;
import org.terasology.world.generation.Facet;
import org.terasology.world.generation.GeneratingRegion;
import org.terasology.world.generation.Produces;
import org.terasology.world.generation.Requires;

import com.google.common.base.Stopwatch;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.Lists;
import com.google.common.math.DoubleMath;

/**
 * TODO Type description
 * @author Martin Steiger
 */
@Produces(GraphFacet.class)
@Requires(@Facet(WorldRegionFacet.class))
public class GraphFacetProvider implements ConfigurableFacetProvider {

    private static final Logger logger = LoggerFactory.getLogger(GraphFacetProvider.class);

    private LoadingCache<WorldRegion, Graph> graphCache = CacheBuilder.newBuilder().build(new CacheLoader<WorldRegion, Graph>() {

        @Override
        public Graph load(WorldRegion wr) throws Exception {
            Stopwatch sw = Stopwatch.createStarted();

            Graph graph = createGraph(wr);

            logger.info("Created graph for {} in {}ms.", wr.getArea(), sw.elapsed(TimeUnit.MILLISECONDS));

            return graph;
        }
    });

    private final LoadingCache<Graph, TriangleLookup> lookupCache = CacheBuilder.newBuilder().build(new CacheLoader<Graph, TriangleLookup>() {

        @Override
        public TriangleLookup load(Graph graph) throws Exception {
            return new TriangleLookup(graph);
        }
    });


    private long seed;

    private GraphProviderConfiguration configuration = new GraphProviderConfiguration();

    @Override
    public void setSeed(long seed) {
        this.seed = seed;
    }

    @Override
    public void process(GeneratingRegion region) {
        Border3D border = region.getBorderForFacet(GraphFacet.class);
        GraphFacetImpl facet = new GraphFacetImpl(region.getRegion(), border);
        WorldRegionFacet regionFacet = region.getRegionFacet(WorldRegionFacet.class);

        Collection<WorldRegion> areas = regionFacet.getRegions();

        for (WorldRegion wr : areas) {
            Graph graph = graphCache.getUnchecked(wr);
            TriangleLookup lookup = lookupCache.getUnchecked(graph);
            facet.add(wr, graph, lookup);
        }

        region.setRegionFacet(GraphFacet.class, facet);
    }

    private Graph createGraph(WorldRegion wr) {
        Rect2i area = wr.getArea();
        if (wr.getType() == RegionType.OCEAN) {
//            int rows = DoubleMath.roundToInt(area.height() / cellSize, RoundingMode.HALF_UP);
//            int cols = DoubleMath.roundToInt(area.width() / cellSize, RoundingMode.HALF_UP);
            return createGridGraph(area, 1, 1);
        } else {
            int numSites = DoubleMath.roundToInt(area.area() / configuration.graphDensity, RoundingMode.HALF_UP);
            return createVoronoiGraph(area, numSites);
        }
    }

    private static Graph createGridGraph(Rect2i bounds, int rows, int cols) {

        Rect2i doubleBounds = Rect2i.createFromMinAndSize(bounds.minX(), bounds.minY(), bounds.width(), bounds.height());
        final Graph graph = new GridGraph(doubleBounds, rows, cols);

        return graph;
    }

    private Graph createVoronoiGraph(Rect2i bounds, int numSites) {

        // use different seeds for different areas
        long areaSeed = seed ^ bounds.hashCode();
        final Random r = new FastRandom(areaSeed);

        List<Vector2f> points = Lists.newArrayListWithCapacity(numSites);
        for (int i = 0; i < numSites; i++) {
            float px = bounds.minX() + r.nextFloat() * bounds.width();
            float py = bounds.minY() + r.nextFloat() * bounds.height();
            points.add(new Vector2f(px, py));
        }

        Rect2f doubleBounds = Rect2f.createFromMinAndSize(bounds.minX(), bounds.minY(), bounds.width(), bounds.height());
        final Voronoi v = new Voronoi(points, doubleBounds);
        final Graph graph = new VoronoiGraph(v, 2);
        GraphEditor.improveCorners(graph.getCorners());

        return graph;
    }

    @Override
    public String getConfigurationName() {
        return "Voronoi Graphs";
    }

    @Override
    public Component getConfiguration() {
        return configuration;
    }

    @Override
    public void setConfiguration(Component configuration) {
        this.configuration = (GraphProviderConfiguration) configuration;
    }

    private static class GraphProviderConfiguration implements Component {
        @Range(min = 100, max = 5000f, increment = 100f, precision = 0, description = "Define the density for graph cells")
        private float graphDensity = 500f;
    }
}
