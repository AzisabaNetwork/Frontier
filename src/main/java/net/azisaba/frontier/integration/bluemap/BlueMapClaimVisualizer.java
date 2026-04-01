package net.azisaba.frontier.integration.bluemap;

import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapMap;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import de.bluecolored.bluemap.api.math.Shape;
import net.azisaba.frontier.domain.ClaimRecord;
import net.azisaba.frontier.domain.ClaimState;
import net.azisaba.frontier.service.FrontierService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class BlueMapClaimVisualizer {
    private static final String MARKER_SET_ID = "frontier-claims";

    private final JavaPlugin plugin;
    private final FrontierService service;
    private Consumer<BlueMapAPI> enableListener;
    private Consumer<BlueMapAPI> disableListener;

    public BlueMapClaimVisualizer(JavaPlugin plugin, FrontierService service) {
        this.plugin = plugin;
        this.service = service;
    }

    public void start() {
        this.enableListener = api -> Bukkit.getScheduler().runTask(this.plugin, () -> this.refresh(api));
        this.disableListener = api -> Bukkit.getScheduler().runTask(this.plugin, () -> this.clear(api));
        BlueMapAPI.onEnable(this.enableListener);
        BlueMapAPI.onDisable(this.disableListener);
        BlueMapAPI.getInstance().ifPresent(api -> Bukkit.getScheduler().runTask(this.plugin, () -> this.refresh(api)));
    }

    public void stop() {
        BlueMapAPI.getInstance().ifPresent(this::clear);
        if (this.enableListener != null) {
            BlueMapAPI.unregisterListener(this.enableListener);
            this.enableListener = null;
        }
        if (this.disableListener != null) {
            BlueMapAPI.unregisterListener(this.disableListener);
            this.disableListener = null;
        }
    }

    public void refresh() {
        BlueMapAPI.getInstance().ifPresent(this::refresh);
    }

    private void refresh(BlueMapAPI api) {
        Optional<Long> activeSeasonId = this.service.getActiveSeason().map(season -> season.id());
        if (activeSeasonId.isEmpty()) {
            this.clear(api);
            return;
        }
        Map<BlueMapWorld, List<ClaimRecord>> claimsByWorld = this.service.allClaims().stream()
                .filter(claim -> claim.seasonId() == activeSeasonId.get())
                .filter(claim -> claim.state().protects())
                .collect(Collectors.groupingBy(claim -> this.resolveBlueMapWorld(api, claim.world())))
                .entrySet().stream()
                .filter(entry -> entry.getKey() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (BlueMapWorld blueMapWorld : api.getWorlds()) {
            List<ClaimRecord> claims = claimsByWorld.getOrDefault(blueMapWorld, List.of());
            for (BlueMapMap map : blueMapWorld.getMaps()) {
                MarkerSet markerSet = this.markerSet(map);
                markerSet.getMarkers().clear();
                for (ClaimRecord claim : claims) {
                    markerSet.put(this.markerId(claim), this.createMarker(claim));
                }
            }
        }
    }

    private void clear(BlueMapAPI api) {
        for (BlueMapWorld world : api.getWorlds()) {
            for (BlueMapMap map : world.getMaps()) {
                map.getMarkerSets().remove(MARKER_SET_ID);
            }
        }
    }

    private MarkerSet markerSet(BlueMapMap map) {
        return map.getMarkerSets().computeIfAbsent(MARKER_SET_ID, ignored -> new MarkerSet("Frontier 保護"));
    }

    private BlueMapWorld resolveBlueMapWorld(BlueMapAPI api, String bukkitWorldName) {
        org.bukkit.World world = Bukkit.getWorld(bukkitWorldName);
        if (world == null) {
            return null;
        }
        return api.getWorld(world).orElse(null);
    }

    private ShapeMarker createMarker(ClaimRecord claim) {
        int minX = claim.chunkX() << 4;
        int minZ = claim.chunkZ() << 4;
        Shape shape = Shape.createRect(minX, minZ, minX + 16, minZ + 16);
        return ShapeMarker.builder()
                .label(this.markerLabel(claim))
                .detail(this.markerDetail(claim))
                .shape(shape, 64f)
                .centerPosition()
                .lineColor(this.lineColor(claim.state()))
                .fillColor(this.fillColor(claim.state()))
                .lineWidth(2)
                .depthTestEnabled(false)
                .sorting(10)
                .build();
    }

    private String markerLabel(ClaimRecord claim) {
        return claim.ownerName() + " の保護";
    }

    private String markerDetail(ClaimRecord claim) {
        String owners = String.join(", ", this.service.claimOwners(claim));
        String members = String.join(", ", this.service.claimMembers(claim));
        if (members.isBlank()) {
            members = "なし";
        }
        return """
                <div>
                  <h3>%s</h3>
                  <p>保護ID: #%d</p>
                  <p>状態: %s</p>
                  <p>共同所有者: %s</p>
                  <p>共有メンバー: %s</p>
                  <p>期限: %s</p>
                  <p>チャンク: %d, %d</p>
                </div>
                """.formatted(
                this.escapeHtml(this.markerLabel(claim)),
                claim.id(),
                this.displayClaimState(claim.state()),
                this.escapeHtml(owners),
                this.escapeHtml(members),
                Objects.toString(claim.expiresAt(), "-"),
                claim.chunkX(),
                claim.chunkZ()
        );
    }

    private String markerId(ClaimRecord claim) {
        return "claim-" + claim.id();
    }

    private Color lineColor(ClaimState state) {
        return switch (state) {
            case ACTIVE -> new Color(0xFF2E8B57);
            case WARNING -> new Color(0xFFFFB000);
            case EXPIRED -> new Color(0xFFE05A47);
            case FROZEN -> new Color(0xFF7A7A7A);
            case ABANDONED -> new Color(0xFF555555);
        };
    }

    private Color fillColor(ClaimState state) {
        return switch (state) {
            case ACTIVE -> new Color(0x552E8B57);
            case WARNING -> new Color(0x55FFB000);
            case EXPIRED -> new Color(0x55E05A47);
            case FROZEN -> new Color(0x557A7A7A);
            case ABANDONED -> new Color(0x33555555);
        };
    }

    private String displayClaimState(ClaimState state) {
        return switch (state) {
            case ACTIVE -> "有効";
            case WARNING -> "期限警告";
            case EXPIRED -> "期限切れ";
            case FROZEN -> "凍結";
            case ABANDONED -> "放棄";
        };
    }

    private String escapeHtml(String value) {
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
