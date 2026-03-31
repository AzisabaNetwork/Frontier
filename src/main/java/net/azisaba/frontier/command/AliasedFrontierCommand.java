package net.azisaba.frontier.command;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;

import java.util.Collection;

public final class AliasedFrontierCommand implements BasicCommand {
    private final FrontierCommand delegate;
    private final String root;

    public AliasedFrontierCommand(FrontierCommand delegate, String root) {
        this.delegate = delegate;
        this.root = root;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        this.delegate.execute(stack, prepend(args));
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        return this.delegate.suggest(stack, prependForSuggest(args));
    }

    @Override
    public String permission() {
        return this.delegate.permission();
    }

    private String[] prepend(String[] args) {
        String[] forwarded = new String[args.length + 1];
        forwarded[0] = this.root;
        System.arraycopy(args, 0, forwarded, 1, args.length);
        return forwarded;
    }

    private String[] prependForSuggest(String[] args) {
        if (args.length == 0) {
            return new String[]{this.root, ""};
        }
        return this.prepend(args);
    }
}
