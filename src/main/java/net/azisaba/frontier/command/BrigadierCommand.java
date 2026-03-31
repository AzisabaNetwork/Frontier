package net.azisaba.frontier.command;

import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.jetbrains.annotations.NotNull;

public interface BrigadierCommand {
    LiteralCommandNode<CommandSourceStack> create(@NotNull String name);
}
