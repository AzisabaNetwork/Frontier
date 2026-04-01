package net.azisaba.frontier.integration.worldguard;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.flags.StateFlag;
import com.sk89q.worldguard.domains.DefaultDomain;
import com.sk89q.worldguard.protection.flags.RegionGroup;
import com.sk89q.worldguard.protection.flags.Flags;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.ProtectedCuboidRegion;
import net.azisaba.frontier.domain.ClaimRecord;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class WorldGuardClaimProtection implements ClaimProtection {
    @Override
    public void createClaimRegion(ClaimRecord claim) {
        RegionManager manager = this.regionManager(claim.world());
        if (manager == null) {
            return;
        }
        int minX = claim.chunkX() << 4;
        int minZ = claim.chunkZ() << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        ProtectedCuboidRegion region = new ProtectedCuboidRegion(
                claim.regionId(),
                BlockVector3.at(minX, -64, minZ),
                BlockVector3.at(maxX, 319, maxZ)
        );
        DefaultDomain owners = new DefaultDomain();
        owners.addPlayer(claim.ownerUuid());
        region.setOwners(owners);
        this.deny(region, Flags.BLOCK_BREAK);
        this.deny(region, Flags.BLOCK_PLACE);
        this.deny(region, Flags.CHEST_ACCESS);
        this.deny(region, Flags.USE);
        this.deny(region, Flags.INTERACT);
        this.deny(region, Flags.ITEM_FRAME_ROTATE);
        this.deny(region, Flags.ENTITY_ITEM_FRAME_DESTROY);
        this.deny(region, Flags.ENTITY_PAINTING_DESTROY);
        this.deny(region, Flags.PISTONS);
        this.deny(region, Flags.WATER_FLOW);
        this.deny(region, Flags.LAVA_FLOW);
        this.deny(region, Flags.TNT);
        this.deny(region, Flags.CREEPER_EXPLOSION);
        this.deny(region, Flags.OTHER_EXPLOSION);
        this.deny(region, Flags.WITHER_DAMAGE);
        this.deny(region, Flags.GHAST_FIREBALL);
        manager.addRegion(region);
    }

    @Override
    public void releaseClaimRegion(ClaimRecord claim) {
        RegionManager manager = this.regionManager(claim.world());
        if (manager != null) {
            manager.removeRegion(claim.regionId());
        }
    }

    @Override
    public void freezeClaimRegion(ClaimRecord claim) {
        RegionManager manager = this.regionManager(claim.world());
        if (manager == null) {
            return;
        }
        var region = manager.getRegion(claim.regionId());
        if (region != null) {
            region.getOwners().clear();
        }
    }

    @Override
    public boolean hasConflictingRegion(String worldName, int chunkX, int chunkZ) {
        RegionManager manager = this.regionManager(worldName);
        if (manager == null) {
            return false;
        }
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        int maxX = minX + 15;
        int maxZ = minZ + 15;
        ProtectedCuboidRegion probe = new ProtectedCuboidRegion(
                "__frontier_probe__",
                BlockVector3.at(minX, -64, minZ),
                BlockVector3.at(maxX, 319, maxZ)
        );
        for (ProtectedRegion region : probe.getIntersectingRegions(manager.getRegions().values())) {
            if ("__global__".equalsIgnoreCase(region.getId())) {
                continue;
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean isOwner(ClaimRecord claim, UUID playerId) {
        ProtectedRegion region = this.region(claim);
        if (region == null) {
            return claim.ownerUuid().equals(playerId);
        }
        return region.getOwners().contains(playerId);
    }

    @Override
    public boolean canEdit(ClaimRecord claim, UUID playerId) {
        ProtectedRegion region = this.region(claim);
        if (region == null) {
            return claim.ownerUuid().equals(playerId);
        }
        return this.isOwner(claim, playerId) || region.getMembers().contains(playerId);
    }

    @Override
    public boolean supportsSharing() {
        return true;
    }

    @Override
    public List<String> owners(ClaimRecord claim) {
        return this.names(this.region(claim) == null ? null : this.region(claim).getOwners());
    }

    @Override
    public List<String> members(ClaimRecord claim) {
        return this.names(this.region(claim) == null ? null : this.region(claim).getMembers());
    }

    @Override
    public void addOwner(ClaimRecord claim, UUID playerId, String lastKnownName) {
        ProtectedRegion region = this.region(claim);
        if (region != null) {
            region.getOwners().addPlayer(playerId);
        }
    }

    @Override
    public void removeOwner(ClaimRecord claim, UUID playerId) {
        ProtectedRegion region = this.region(claim);
        if (region != null) {
            region.getOwners().removePlayer(playerId);
        }
    }

    @Override
    public void addMember(ClaimRecord claim, UUID playerId, String lastKnownName) {
        ProtectedRegion region = this.region(claim);
        if (region != null) {
            region.getMembers().addPlayer(playerId);
        }
    }

    @Override
    public void removeMember(ClaimRecord claim, UUID playerId) {
        ProtectedRegion region = this.region(claim);
        if (region != null) {
            region.getMembers().removePlayer(playerId);
        }
    }

    @Override
    public String mode() {
        return "worldguard";
    }

    private RegionManager regionManager(String worldName) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }
        return WorldGuard.getInstance().getPlatform().getRegionContainer().get(BukkitAdapter.adapt(world));
    }

    private void deny(ProtectedCuboidRegion region, StateFlag flag) {
        region.setFlag(flag, StateFlag.State.DENY);
        if (flag.getRegionGroupFlag() != null) {
            region.setFlag(flag.getRegionGroupFlag(), RegionGroup.NON_MEMBERS);
        }
    }

    private ProtectedRegion region(ClaimRecord claim) {
        RegionManager manager = this.regionManager(claim.world());
        return manager == null ? null : manager.getRegion(claim.regionId());
    }

    private List<String> names(DefaultDomain domain) {
        if (domain == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (UUID uuid : domain.getUniqueIds()) {
            String name = Bukkit.getOfflinePlayer(uuid).getName();
            names.add(name == null ? uuid.toString() : name);
        }
        for (String name : domain.getPlayers()) {
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }
}
