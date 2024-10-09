/*
 * This file is part of LuckPerms, licensed under the MIT License.
 *
 *  Copyright (c) lucko (Luck) <luck@lucko.me>
 *  Copyright (c) contributors
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in all
 *  copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 *  SOFTWARE.
 */

package me.lucko.luckperms.migration;

import net.luckperms.api.LuckPerms;
import net.luckperms.api.model.group.Group;
import net.luckperms.api.model.user.User;
import net.luckperms.api.node.types.*;

import nl.svenar.powerranks.bukkit.data.Users;
import nl.svenar.powerranks.bukkit.PowerRanks;
import nl.svenar.powerranks.api.PowerRanksAPI;

import nl.svenar.powerranks.common.structure.PRPermission;
import nl.svenar.powerranks.common.structure.PRPlayer;
import nl.svenar.powerranks.common.structure.PRPlayerRank;
import nl.svenar.powerranks.common.structure.PRRank;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

public final class MigrationPowerRanks extends MigrationJavaPlugin {
    private LuckPerms luckPerms;
    private PowerRanks pr;

    @Override
    public void onEnable() {
        this.luckPerms = getServer().getServicesManager().load(LuckPerms.class);
        this.pr = JavaPlugin.getPlugin(PowerRanks.class);
    }

    @Override
    public void runMigration(CommandSender sender, String[] args) {
        log(sender, "Starting.");

        PowerRanksAPI prApi = PowerRanks.getAPI();
        Users prUsers = new Users(pr);

        // Migrate all groups
        log(sender, "Starting groups migration.");
        List<PRRank> ranks = prApi.getRanksAPI().getRanks();
        AtomicInteger groupCount = new AtomicInteger(0);
        for (PRRank rank : ranks) {
            Group group = this.luckPerms.getGroupManager().createAndLoadGroup(rank.getName()).join();

            for (PRPermission node : prApi.getRanksAPI().getPermissions(rank)) {
                if (node.getName().isEmpty()) continue;
                group.data().add(MigrationUtils.parseNode(node.getName(), node.getValue()).build());
            }

            for (PRRank parent : prApi.getRanksAPI().getInheritances(rank)) {
                if (parent.getName().isEmpty()) continue;
                group.data().add(InheritanceNode.builder(MigrationUtils.standardizeName(parent.getName())).build());
            }

            group.data().add(WeightNode.builder().weight(rank.getWeight()).build());
            group.data().add(PrefixNode.builder().prefix(rank.getPrefix()).priority(rank.getWeight()).build());
            group.data().add(SuffixNode.builder().suffix(rank.getSuffix()).priority(rank.getWeight()).build());

            this.luckPerms.getGroupManager().saveGroup(group);
            log(sender, "Migrated " + groupCount.incrementAndGet() + " groups so far.");
        }
        log(sender, "Migrated " + groupCount.get() + " groups.");

        // Migrate all users
        log(sender, "Starting user migration.");
        List<PRPlayer> players = prUsers.getCachedPlayers();
        AtomicInteger userCount = new AtomicInteger(0);
        for (PRPlayer player : players) {
            UUID uuid = player.getUUID();
            if (uuid == null) continue;

            User user = this.luckPerms.getUserManager().loadUser(uuid, null).join();

            for (PRPlayerRank rank : player.getRanks()) {
                if (rank.getName().isEmpty()) continue;
                InheritanceNode.Builder builder = InheritanceNode.builder(MigrationUtils.standardizeName(rank.getName()));

                for (String tag : rank.getTags().keySet()) { // I'm assuming those are contexts, right? There's no documentation about these "tags"
                    if (tag.equalsIgnoreCase("all")) continue;
                    builder.withContext(tag, String.valueOf(rank.getTags().get(tag)));
                }

                user.data().add(builder.build());
            }

            for (String tag : player.getUsertags()) {
                user.data().add(MetaNode.builder().key(tag).value(prUsers.getUserTagValue(tag)).build());
            }

            for (PRPermission permission : player.getPermissions()) {
                if (permission.getName().isEmpty()) continue;
                user.data().add(MigrationUtils.parseNode(permission.getName(), permission.getValue()).build());
            }

            this.luckPerms.getUserManager().cleanupUser(user);
            this.luckPerms.getUserManager().saveUser(user);
            if (userCount.incrementAndGet() % 500 == 0) {
                log(sender, "Migrated " + userCount.get() + " users so far.");
            }
        }

        log(sender, "Migrated " + userCount.get() + " users.");
        log(sender, "Success! Migration complete.");
        log(sender, "Don't forget to remove the PowerRanks jar from your plugins folder & restart the server. " +
                "LuckPerms may not take over as the server permission handler until this is done.");
    }

}
