package com.example.visualclaims;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.dynmap.DynmapAPI;
import org.dynmap.markers.AreaMarker;
import org.dynmap.markers.MarkerAPI;
import org.dynmap.markers.MarkerSet;

import java.util.*;

public class DynmapHook {
    private final VisualClaims plugin;
    private DynmapAPI dynmap;
    private MarkerAPI markerApi;
    private MarkerSet markerSet;

    // chunkId -> AreaMarker
    private final Map<String, AreaMarker> markersByChunk = new HashMap<>();

    public DynmapHook(VisualClaims plugin) {
        this.plugin = plugin;
    }

    public boolean hook() {
        var p = Bukkit.getPluginManager().getPlugin("dynmap");
        if (p == null) return false;
        if (!(p instanceof DynmapAPI api)) return false;
        this.dynmap = api;
        this.markerApi = dynmap.getMarkerAPI();
        if (markerApi == null) return false;
        markerSet = markerApi.getMarkerSet("visualclaims.towns");
        if (markerSet == null) markerSet = markerApi.createMarkerSet("visualclaims.towns", "Towns", null, false);
        return markerSet != null;
    }

    public void clearAll() {
        for (AreaMarker m : new ArrayList<>(markersByChunk.values())) {
            m.deleteMarker();
        }
        markersByChunk.clear();
    }

    public void removeAreaMarker(ChunkPos pos) {
        AreaMarker m = markersByChunk.remove(pos.id());
        if (m != null) m.deleteMarker();
    }

    public void addOrUpdateChunkArea(Town t, ChunkPos pos) {
        if (markerApi == null || markerSet == null) return;
        World w = Bukkit.getWorld(pos.getWorld());
        if (w == null) return;

        String id = pos.id();
        AreaMarker m = markersByChunk.get(id);

        int bx = pos.getX() * 16;
        int bz = pos.getZ() * 16;
        double[] x = new double[]{bx, bx + 16, bx + 16, bx};
        double[] y = new double[]{bz, bz, bz + 16, bz + 16};

        if (m == null) {
            m = markerSet.createAreaMarker(id, t.getName(), false, pos.getWorld(), x, y, false);
            markersByChunk.put(id, m);
        } else {
            m.setCornerLocations(x, y);
            m.setLabel(t.getName());
        }

        VanillaColor c = t.getColor();
        int rgb = (c != null) ? c.rgb : VanillaColor.GREEN.rgb;
        int lineWeight = plugin.getConfig().getInt("line-weight", plugin.getConfig().getInt("line-weight", 2));
        double lineOpacity = plugin.getConfig().getDouble("line-opacity", plugin.getConfig().getDouble("line-opacity", 0.9));
        double fillOpacity = plugin.getConfig().getDouble("fill-opacity", plugin.getConfig().getDouble("fill-opacity", 0.35));
        m.setLineStyle(lineWeight, lineOpacity, rgb);
        m.setFillStyle(fillOpacity, rgb);
    }

    public void refreshTownAreas(Town t) {
        for (ChunkPos pos : t.getClaims()) addOrUpdateChunkArea(t, pos);
    }

    public void refreshAllTownAreas(TownManager tm) {
        for (Town t : tm.allTowns()) refreshTownAreas(t);
    }
}
