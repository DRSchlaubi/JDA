/*
 * Copyright 2015 Austin Keener, Michael Ritter, Florian Spieß, and the JDA contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.dv8tion.jda.internal.entities;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.attribute.ICategorizableChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildMessageChannelUnion;
import net.dv8tion.jda.api.entities.channel.unions.MessageChannelUnion;
import net.dv8tion.jda.api.entities.emoji.CustomEmoji;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import net.dv8tion.jda.api.entities.sticker.StickerItem;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.exceptions.PermissionException;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.LayoutComponent;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import net.dv8tion.jda.api.requests.restaction.ThreadChannelAction;
import net.dv8tion.jda.api.requests.restaction.pagination.ReactionPaginationAction;
import net.dv8tion.jda.api.utils.AttachedFile;
import net.dv8tion.jda.api.utils.MarkdownSanitizer;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.messages.MessageEditData;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.requests.CompletedRestAction;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import net.dv8tion.jda.internal.requests.Route;
import net.dv8tion.jda.internal.requests.restaction.AuditableRestActionImpl;
import net.dv8tion.jda.internal.requests.restaction.MessageEditActionImpl;
import net.dv8tion.jda.internal.utils.Checks;
import net.dv8tion.jda.internal.utils.EncodingUtil;
import net.dv8tion.jda.internal.utils.EntityString;
import net.dv8tion.jda.internal.utils.Helpers;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ReceivedMessage extends AbstractMessage
{
    public static boolean didContentIntentWarning = false;
    private final Object mutex = new Object();

    protected final JDAImpl api;
    protected final long id;
    protected final long channelId;
    protected final long applicationId;
    protected final MessageType type;
    protected final MessageChannel channel;
    protected final Guild guild;
    protected final MessageReference messageReference;
    protected final boolean fromWebhook;
    protected final boolean pinned;
    protected final User author;
    protected final Member member;
    protected final MessageActivity activity;
    protected final OffsetDateTime editedTime;
    protected final Mentions mentions;
    protected final List<MessageReaction> reactions;
    protected final List<Attachment> attachments;
    protected final List<MessageEmbed> embeds;
    protected final List<StickerItem> stickers;
    protected final List<LayoutComponent> components;
    protected final int flags;
    protected final Message.Interaction interaction;
    protected final ThreadChannel startedThread;

    protected InteractionHook interactionHook;

    // LAZY EVALUATED
    protected String altContent = null;
    protected String strippedContent = null;

    protected List<String> invites = null;

    public ReceivedMessage(
            long id, long channelId, JDA jda, Guild guild, MessageChannel channel, MessageType type, MessageReference messageReference,
            boolean fromWebhook, long applicationId, boolean  tts, boolean pinned,
            String content, String nonce, User author, Member member, MessageActivity activity, OffsetDateTime editTime,
            Mentions mentions, List<MessageReaction> reactions, List<Attachment> attachments, List<MessageEmbed> embeds,
            List<StickerItem> stickers, List<ActionRow> components,
            int flags, Message.Interaction interaction, ThreadChannel startedThread)
    {
        super(content, nonce, tts);
        this.id = id;
        this.channelId = channelId;
        this.channel = channel;
        this.guild = guild;
        this.messageReference = messageReference;
        this.type = type;
        this.api = (JDAImpl) jda;
        this.fromWebhook = fromWebhook;
        this.applicationId = applicationId;
        this.pinned = pinned;
        this.author = author;
        this.member = member;
        this.activity = activity;
        this.editedTime = editTime;
        this.mentions = mentions;
        this.reactions = Collections.unmodifiableList(reactions);
        this.attachments = Collections.unmodifiableList(attachments);
        this.embeds = Collections.unmodifiableList(embeds);
        this.stickers = Collections.unmodifiableList(stickers);
        this.components = Collections.unmodifiableList(components);
        this.flags = flags;
        this.interaction = interaction;
        this.startedThread = startedThread;
    }

    private void checkIntent()
    {
        // Checks whether access to content is limited and the message content intent is not enabled
        if (!didContentIntentWarning && !api.isIntent(GatewayIntent.MESSAGE_CONTENT))
        {
            SelfUser selfUser = api.getSelfUser();
            if (!Objects.equals(selfUser, author) && !mentions.getUsers().contains(selfUser) && isFromGuild())
            {
                didContentIntentWarning = true;
                JDAImpl.LOG.warn(
                    "Attempting to access message content without GatewayIntent.MESSAGE_CONTENT.\n" +
                    "Discord now requires to explicitly enable access to this using the MESSAGE_CONTENT intent.\n" +
                    "Useful resources to learn more:\n" +
                    "\t- https://support-dev.discord.com/hc/en-us/articles/4404772028055-Message-Content-Privileged-Intent-FAQ\n" +
                    "\t- https://jda.wiki/using-jda/gateway-intents-and-member-cache-policy/\n" +
                    "\t- https://jda.wiki/using-jda/troubleshooting/#cannot-get-message-content-attempting-to-access-message-content-without-gatewayintent\n" +
                    "Or suppress this warning if this is intentional with Message.suppressContentIntentWarning()"
                );
            }
        }
    }

    public ReceivedMessage withHook(InteractionHook hook)
    {
        this.interactionHook = hook;
        return this;
    }

    @Nonnull
    @Override
    public JDA getJDA()
    {
        return api;
    }

    @Nullable
    @Override
    public MessageReference getMessageReference()
    {
        return messageReference;
    }

    @Override
    public boolean isPinned()
    {
        return pinned;
    }

    @Nonnull
    @Override
    public RestAction<Void> pin()
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot pin ephemeral messages.");

        if (hasChannel())
            return getChannel().pinMessageById(getIdLong());

        Route.CompiledRoute route = Route.Messages.ADD_PINNED_MESSAGE.compile(getChannelId(), getId());
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> unpin()
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot unpin ephemeral messages.");

        if (hasChannel())
            return getChannel().unpinMessageById(getIdLong());

        Route.CompiledRoute route = Route.Messages.REMOVE_PINNED_MESSAGE.compile(getChannelId(), getId());
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> addReaction(@Nonnull Emoji emoji)
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot add reactions to ephemeral messages.");
        
        Checks.notNull(emoji, "Emoji");

        if (hasChannel())
        {
            boolean missingReaction = reactions.stream()
                    .map(MessageReaction::getEmoji)
                    .noneMatch(r -> r.getAsReactionCode().equals(emoji.getAsReactionCode()));

            if (missingReaction && emoji instanceof RichCustomEmoji)
            {
                Checks.check(((RichCustomEmoji) emoji).canInteract(getJDA().getSelfUser(), getChannel()),
                        "Cannot react with the provided emoji because it is not available in the current getChannel().");
            }

            return getChannel().addReactionById(getId(), emoji);
        }

        String encoded = EncodingUtil.encodeReaction(emoji.getAsReactionCode());
        Route.CompiledRoute route = Route.Messages.ADD_REACTION.compile(getChannelId(), getId(), encoded, "@me");
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> clearReactions()
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot clear reactions from ephemeral messages.");
        if (!isFromGuild())
            throw new IllegalStateException("Cannot clear reactions from a message in a Group or PrivateChannel.");

        if (channel instanceof GuildMessageChannel)
            return ((GuildMessageChannel) channel).clearReactionsById(getId());

        Route.CompiledRoute route = Route.Messages.REMOVE_ALL_REACTIONS.compile(getChannelId(), getId());
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> clearReactions(@Nonnull Emoji emoji)
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot clear reactions from ephemeral messages.");
        if (!isFromGuild())
            throw new IllegalStateException("Cannot clear reactions from a message in a Group or PrivateChannel.");

        if (channel instanceof GuildMessageChannel)
            return ((GuildMessageChannel) channel).clearReactionsById(getId(), emoji);

        String encoded = EncodingUtil.encodeReaction(emoji.getAsReactionCode());
        Route.CompiledRoute route = Route.Messages.CLEAR_EMOJI_REACTIONS.compile(getChannelId(), getId(), encoded);
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> removeReaction(@Nonnull Emoji emoji)
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot remove reactions from ephemeral messages.");

        if (hasChannel())
            return getChannel().removeReactionById(getId(), emoji);

        String encoded = EncodingUtil.encodeReaction(emoji.getAsReactionCode());
        Route.CompiledRoute route = Route.Messages.REMOVE_REACTION.compile(getChannelId(), getId(), encoded, "@me");
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public RestAction<Void> removeReaction(@Nonnull Emoji emoji, @Nonnull User user)
    {
        Checks.notNull(user, "User");
        if (isEphemeral())
            throw new IllegalStateException("Cannot remove reactions from ephemeral messages.");

        // check if the passed user is the SelfUser, then the ChannelType doesn't matter and
        // we can safely remove that
        if (user.equals(getJDA().getSelfUser()))
            return removeReaction(emoji);

        if (!isFromGuild())
            throw new IllegalStateException("Cannot remove reactions of others from a message in a Group or PrivateChannel.");

        if (channel instanceof GuildMessageChannel)
            return ((GuildMessageChannel) channel).removeReactionById(getIdLong(), emoji, user);

        String encoded = EncodingUtil.encodeReaction(emoji.getAsReactionCode());
        Route.CompiledRoute route = Route.Messages.REMOVE_REACTION.compile(getChannelId(), getId(), encoded, user.getId());
        return new RestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public ReactionPaginationAction retrieveReactionUsers(@Nonnull Emoji emoji)
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot retrieve reactions on ephemeral messages.");

        // TODO: Nullable channel for this class
        return getChannel().retrieveReactionUsersById(id, emoji);
    }

    @Nullable
    @Override
    public MessageReaction getReaction(@Nonnull Emoji emoji)
    {
        Checks.notNull(emoji, "Emoji");
        String code = emoji.getAsReactionCode();
        return this.reactions.stream()
                .filter(r -> code.equals(r.getEmoji().getAsReactionCode()))
                .findFirst().orElse(null);
    }

    @Nonnull
    @Override
    public MessageType getType()
    {
        return type;
    }

    @Nullable
    @Override
    public Interaction getInteraction()
    {
        return interaction;
    }

    @Override
    public long getIdLong()
    {
        return id;
    }

    @Nonnull
    @Override
    public String getJumpUrl()
    {
        return Helpers.format(Message.JUMP_URL, isFromGuild() ? getGuild().getId() : "@me", getChannelId(), getId());
    }

    @Override
    public boolean isEdited()
    {
        return editedTime != null;
    }

    @Override
    public OffsetDateTime getTimeEdited()
    {
        return editedTime;
    }

    @Nonnull
    @Override
    public User getAuthor()
    {
        return author;
    }

    @Override
    public Member getMember()
    {
        return member;
    }

    @Nonnull
    @Override
    public String getContentStripped()
    {
        if (strippedContent != null)
            return strippedContent;
        synchronized (mutex)
        {
            if (strippedContent != null)
                return strippedContent;
            return strippedContent = MarkdownSanitizer.sanitize(getContentDisplay());
        }
    }

    @Nonnull
    @Override
    public String getContentDisplay()
    {
        if (altContent != null)
            return altContent;

        synchronized (mutex)
        {
            if (altContent != null)
                return altContent;
            String tmp = getContentRaw();
            for (User user : mentions.getUsers())
            {
                String name;
                if (isFromGuild() && getGuild().isMember(user))
                    name = getGuild().getMember(user).getEffectiveName();
                else
                    name = user.getName();
                tmp = tmp.replaceAll("<@!?" + Pattern.quote(user.getId()) + '>', '@' + Matcher.quoteReplacement(name));
            }
            for (CustomEmoji emoji : mentions.getCustomEmojis())
            {
                tmp = tmp.replace(emoji.getAsMention(), ":" + emoji.getName() + ":");
            }
            for (GuildChannel mentionedChannel : mentions.getChannels())
            {
                tmp = tmp.replace(mentionedChannel.getAsMention(), '#' + mentionedChannel.getName());
            }
            for (Role mentionedRole : mentions.getRoles())
            {
                tmp = tmp.replace(mentionedRole.getAsMention(), '@' + mentionedRole.getName());
            }
            return altContent = tmp;
        }
    }

    @Nonnull
    @Override
    public String getContentRaw()
    {
        checkIntent();
        return content;
    }

    @Nonnull
    @Override
    public List<String> getInvites()
    {
        if (invites != null)
            return invites;
        synchronized (mutex)
        {
            if (invites != null)
                return invites;
            invites = new ArrayList<>();
            Matcher m = INVITE_PATTERN.matcher(getContentRaw());
            while (m.find())
                invites.add(m.group(1));
            return invites = Collections.unmodifiableList(invites);
        }
    }

    @Override
    public String getNonce()
    {
        return nonce;
    }

    @Override
    public boolean isFromType(@Nonnull ChannelType type)
    {
        return getChannelType() == type;
    }

    @Override
    public boolean isFromGuild()
    {
        return guild != null;
    }

    @Nonnull
    @Override
    public ChannelType getChannelType()
    {
        return getChannel().getType();
    }

    @Nonnull
    @Override
    public MessageChannelUnion getChannel()
    {
        if (channel != null)
            return (MessageChannelUnion) channel;
        throw new IllegalStateException("Channel is unavailable in this context. Use getChannelIdLong() instead!");
    }

    @Nonnull
    @Override
    public GuildMessageChannelUnion getGuildChannel()
    {
        if (channel == null || channel instanceof GuildMessageChannelUnion)
            return (GuildMessageChannelUnion) getChannel();
        throw new IllegalStateException("This message was not sent in a guild.");
    }

    @Override
    public Category getCategory()
    {
        GuildMessageChannel chan = getGuildChannel();
        return chan instanceof ICategorizableChannel
            ? ((ICategorizableChannel) chan).getParentCategory()
            : null;
    }

    @Nonnull
    @Override
    public Guild getGuild()
    {
        return guild;
    }

    @Nonnull
    @Override
    public List<Attachment> getAttachments()
    {
        checkIntent();
        return attachments;
    }

    @Nonnull
    @Override
    public List<MessageEmbed> getEmbeds()
    {
        checkIntent();
        return embeds;
    }

    @Nonnull
    @Override
    public List<LayoutComponent> getComponents()
    {
        checkIntent();
        return components;
    }

    @Nonnull
    @Override
    public Mentions getMentions()
    {
        return mentions;
    }

    @Nonnull
    @Override
    public List<MessageReaction> getReactions()
    {
        return reactions;
    }

    @Nonnull
    @Override
    public List<StickerItem> getStickers()
    {
        return this.stickers;
    }

    @Override
    public boolean isWebhookMessage()
    {
        return fromWebhook;
    }

    @Override
    public long getApplicationIdLong()
    {
        return applicationId;
    }

    @Override
    public boolean hasChannel()
    {
        return channel != null;
    }

    @Override
    public long getChannelIdLong()
    {
        return channelId;
    }

    @Override
    public boolean isTTS()
    {
        return isTTS;
    }

    @Nullable
    @Override
    public MessageActivity getActivity()
    {
        return activity;
    }

    @Nonnull
    @Override
    public MessageEditAction editMessage(@Nonnull CharSequence newContent)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageById(getId(), newContent);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).setContent(newContent.toString());
    }

    @Nonnull
    @Override
    public MessageEditAction editMessageEmbeds(@Nonnull Collection<? extends MessageEmbed> embeds)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageEmbedsById(getId(), embeds);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).setEmbeds(embeds);
    }

    @Nonnull
    @Override
    public MessageEditAction editMessageComponents(@Nonnull Collection<? extends LayoutComponent> components)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageComponentsById(getId(), components);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).setComponents(components);
    }

    @Nonnull
    @Override
    public MessageEditAction editMessageFormat(@Nonnull String format, @Nonnull Object... args)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageFormatById(getId(), format, args);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).setContent(String.format(format, args));
    }

    @Nonnull
    @Override
    public MessageEditAction editMessageAttachments(@Nonnull Collection<? extends AttachedFile> attachments)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageAttachmentsById(getId(), attachments);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).setAttachments(attachments);
    }

    @Nonnull
    @Override
    public MessageEditAction editMessage(@Nonnull MessageEditData newContent)
    {
        checkUser();

        MessageEditActionImpl action;
        if (hasChannel())
        {
            if (interactionHook == null) // only perform perm checks if its not an interaction
                action = (MessageEditActionImpl) getChannel().editMessageById(getId(), newContent);
            else
                action = new MessageEditActionImpl(getChannel(), getId());
        }
        else
        {
            action = new MessageEditActionImpl(getJDA(), getGuild(), getChannelId(), getId());
        }

        return action.withHook(this.interactionHook).applyData(newContent);
    }

    private void checkUser()
    {
        if (!getJDA().getSelfUser().equals(getAuthor()))
            throw new IllegalStateException("Attempted to update message that was not sent by this account. You cannot modify other User's messages!");
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> delete()
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot delete ephemeral messages.");
        if (!type.canDelete())
            throw new IllegalStateException("Cannot delete messages of type " + type);

        if (!getJDA().getSelfUser().equals(getAuthor()))
        {
            if (!isFromGuild())
                throw new IllegalStateException("Cannot delete another User's messages in a PrivateChannel.");

            if (hasChannel())
            {
                GuildMessageChannel gChan = getGuildChannel();
                Member sMember = getGuild().getSelfMember();
                Checks.checkAccess(sMember, gChan);
                if (!sMember.hasPermission(gChan, Permission.MESSAGE_MANAGE))
                    throw new InsufficientPermissionException(gChan, Permission.MESSAGE_MANAGE);
            }
        }

        if (hasChannel())
            return getChannel().deleteMessageById(getIdLong());

        Route.CompiledRoute route = Route.Messages.DELETE_MESSAGE.compile(getChannelId(), getId());
        return new AuditableRestActionImpl<>(getJDA(), route);
    }

    @Nonnull
    @Override
    public AuditableRestAction<Void> suppressEmbeds(boolean suppressed)
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot suppress embeds on ephemeral messages.");
        
        if (!getJDA().getSelfUser().equals(getAuthor()))
        {
            if (!isFromGuild())
                throw new PermissionException("Cannot suppress embeds of others in a PrivateChannel.");

            if (hasChannel())
            {
                GuildMessageChannel gChan = getGuildChannel();
                if (!getGuild().getSelfMember().hasPermission(gChan, Permission.MESSAGE_MANAGE))
                    throw new InsufficientPermissionException(gChan, Permission.MESSAGE_MANAGE);
            }
        }

        JDAImpl jda = (JDAImpl) getJDA();
        Route.CompiledRoute route = Route.Messages.EDIT_MESSAGE.compile(getChannelId(), getId());
        int newFlags = flags;
        int suppressionValue = MessageFlag.EMBEDS_SUPPRESSED.getValue();
        if (suppressed)
            newFlags |= suppressionValue;
        else
            newFlags &= ~suppressionValue;
        return new AuditableRestActionImpl<>(jda, route, DataObject.empty().put("flags", newFlags));
    }

    @Nonnull
    @Override
    public RestAction<Message> crosspost()
    {
        if (isEphemeral())
            throw new IllegalStateException("Cannot crosspost ephemeral messages.");
        
        if (getFlags().contains(MessageFlag.CROSSPOSTED))
            return new CompletedRestAction<>(getJDA(), this);

        MessageChannelUnion channel = getChannel();
        if (!(channel instanceof NewsChannel))
            throw new IllegalStateException("This message was not sent in a news channel");
        NewsChannel newsChannel = (NewsChannel) channel;
        Checks.checkAccess(getGuild().getSelfMember(), newsChannel);
        if (!getAuthor().equals(getJDA().getSelfUser()) && !getGuild().getSelfMember().hasPermission(newsChannel, Permission.MESSAGE_MANAGE))
            throw new InsufficientPermissionException(newsChannel, Permission.MESSAGE_MANAGE);
        return newsChannel.crosspostMessageById(getId());
    }

    @Override
    public boolean isSuppressedEmbeds()
    {
        return (this.flags & MessageFlag.EMBEDS_SUPPRESSED.getValue()) > 0;
    }

    @Nonnull
    @Override
    public EnumSet<MessageFlag> getFlags()
    {
        return MessageFlag.fromBitField(flags);
    }

    @Override
    public long getFlagsRaw()
    {
        return flags;
    }

    @Override
    public boolean isEphemeral()
    {
        return (this.flags & MessageFlag.EPHEMERAL.getValue()) != 0;
    }

    @Nullable
    @Override
    public ThreadChannel getStartedThread()
    {
        return this.startedThread;
    }

    @Override
    public ThreadChannelAction createThreadChannel(String name)
    {
        return getGuildChannel().asThreadContainer().createThreadChannel(name, this.getIdLong());
    }

    @Override
    public boolean equals(Object o)
    {
        if (o == this)
            return true;
        if (!(o instanceof ReceivedMessage))
            return false;
        ReceivedMessage oMsg = (ReceivedMessage) o;
        return this.id == oMsg.id;
    }

    @Override
    public int hashCode()
    {
        return Long.hashCode(id);
    }

    @Override
    public String toString()
    {
        return new EntityString(this)
                .addMetadata("author", author.getAsTag())
                .addMetadata("content", String.format("%.20s ...", this))
                .toString();
    }

    @Override
    protected void unsupported()
    {
        throw new UnsupportedOperationException("This operation is not supported on received messages!");
    }

    @Override
    public void formatTo(Formatter formatter, int flags, int width, int precision)
    {
        boolean upper = (flags & FormattableFlags.UPPERCASE) == FormattableFlags.UPPERCASE;
        boolean leftJustified = (flags & FormattableFlags.LEFT_JUSTIFY) == FormattableFlags.LEFT_JUSTIFY;
        boolean alt = (flags & FormattableFlags.ALTERNATE) == FormattableFlags.ALTERNATE;

        String out = alt ? getContentRaw() : getContentDisplay();

        if (upper)
            out = out.toUpperCase(formatter.locale());

        appendFormat(formatter, width, precision, leftJustified, out);
    }
}
