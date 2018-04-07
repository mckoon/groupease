package io.github.groupease.restendpoint;

import javax.annotation.Nonnull;
import javax.inject.Provider;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

import com.codahale.metrics.annotation.Timed;
import javax.inject.Inject;

import io.github.groupease.auth.CurrentUserId;
import io.github.groupease.db.DataAccess;
import io.github.groupease.exception.*;
import io.github.groupease.model.Group;
import io.github.groupease.model.Member;
import io.github.groupease.model.GroupeaseUser;
import io.github.groupease.user.UserNotFoundException;
import io.github.groupease.util.GroupCreateWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.List;

/**
 * REST-ful web service for {@link Group} instances.
 */
@Path("channels/{channelId}/groups")
@Produces(MediaType.APPLICATION_JSON)
public class GroupWebService {
    private static final Logger LOGGER = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final DataAccess dataAccess;
    private final Provider<String> currentUserIdProvider;
    private GroupeaseUser currentUser;

    @Inject
    public GroupWebService(@Nonnull DataAccess dataAccess,
                           @Nonnull @CurrentUserId Provider<String> currentUserIdProvider)
    {
        this.dataAccess = dataAccess;
        this.currentUserIdProvider = currentUserIdProvider;
    }

    /**
     * Gets a list of all {@link Group}s in the channel. User must be a member of the channel to retrieve the list
     * @param channelId The ID of the channel to get the list of groups in
     * @return The list of groups. An empty list is returned if there are no groups
     */
    @GET
    @Timed
    @Nonnull
    public List<Group> list(@PathParam("channelId") long channelId)
    {
        LOGGER.debug("GroupWebService.list({})", channelId);

        // Make sure that caller is a member of channel
        verifyCurrentUserIsChannelMember(channelId);

        return dataAccess.group().list(channelId);
    }

    /**
     * Retrieves a specific {@link Group} in a channel. Only channel members can retrieve a group
     * @param channelId The ID of the channel the group is in
     * @param groupId The ID of the group
     * @return The specified group
     */
    @GET
    @Path("{groupId}")
    @Timed
    @Nonnull
    public Group getById(@PathParam("channelId") long channelId, @PathParam("groupId") long groupId)
    {
        LOGGER.debug("GroupWebService.getById(channel={}, group={})", channelId, groupId);

        // Make sure that caller is a member of channel
        verifyCurrentUserIsChannelMember(channelId);

        // Get the group from the database. Verify that it belongs to the specified channel before returning it
        Group group = dataAccess.group().get(groupId);
        if(group == null || group.getChannelId() != channelId)
        {
            throw new GroupNotFoundException();
        }

        return group;
    }

    /**
     * Creates a new {@link Group} in the channel. Only channel members can create a group
     * @param channelId The ID of the channel to create the group in
     * @param newGroup A {@link GroupCreateWrapper} that contains the group name and other POSTed JSON values
     * @return The newly created group
     */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Group create(@PathParam("channelId") long channelId, @Nonnull GroupCreateWrapper newGroup)
    {
        LOGGER.debug("GroupWebService.create(channelUrl={}, name=)", channelId, newGroup.name);

        // Make sure that caller is a member of channel
        verifyCurrentUserIsChannelMember(channelId);

        // The caller must not supply an ID. One will be generated by the database
        if(newGroup.id != null)
        {
            throw new InvalidGroupIdException("No group ID should be supplied in the POSTed data");
        }

        // If a channel ID is supplied, it must match the URL
        if(newGroup.channel != null && channelId != newGroup.channel)
        {
            throw new InvalidChannelIdException("If a channelID is supplied in the POSTed data it must match the channel ID in the URL");
        }

        // A group name must be supplied
        if(newGroup.name == null || newGroup.name.isEmpty())
        {
            throw new GroupNameMissingException();
        }

        // Check if a group with this name already exists in the channel
        if(dataAccess.group().get(newGroup.name, channelId) != null)
        {
            throw new GroupNameConflictException();
        }

        // Find the user's member object so it can be added to the new group
        Member currentUserMember = currentUser.getMemberList().stream()
                .filter(member -> member.getChannel().getId() == channelId).findFirst().get();

        return dataAccess.group().create(channelId, newGroup.name, newGroup.description, currentUserMember);
    }

    /**
     * Updates a {@link Group}
     * @param channelId The ID of the channel the group is part of
     * @param groupId The ID of the group
     * @param updateGroup The {@link GroupCreateWrapper} object that contains the uploaded JSON wrapped fields
     * @return The revised group object
     */
    @PUT
    @Path("{groupId}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Group update(@PathParam("channelId") long channelId, @PathParam("groupId") long groupId,
                        @Nonnull GroupCreateWrapper updateGroup)
    {
        LOGGER.debug("GroupWebService.update(channelUrl={}, name={})", channelId, updateGroup.name);

        // Make sure that caller is a member of channel
        verifyCurrentUserIsChannelMember(channelId);

        // The caller must supply an ID of an existing group
        if(updateGroup.id == null)
        {
            throw new InvalidGroupIdException("JSON group ID must be supplied");
        }
        if(updateGroup.id != groupId)
        {
            throw new InvalidGroupIdException("JSON group ID " + updateGroup.id + "must match group ID " + groupId + " in URL");
        }

        // The channel ID must be supplied and it must match the URL
        if(updateGroup.channel == null)
        {
            throw new InvalidChannelIdException("The channel ID must be supplied in the JSON");
        }
        if(channelId != updateGroup.channel)
        {
            throw new InvalidChannelIdException("JSON channel ID " + updateGroup.channel +
                    " must match the channel ID " + channelId + "in the URL");
        }

        // A group name must be supplied and not empty
        if(updateGroup.name == null || updateGroup.name.isEmpty())
        {
            throw new GroupNameMissingException();
        }

        // Check if a group with this new name already exists in the channel
        if(dataAccess.group().get(updateGroup.name, channelId) != null)
        {
            throw new GroupNameConflictException();
        }

        // Get the existing group
        Group existingGroup = dataAccess.group().get(updateGroup.id);
        if(existingGroup == null)
        {
            throw new GroupNotFoundException();
        }

        // Updates should only be allowed by a member of the group
        if(existingGroup.getMembers().stream()
                .noneMatch(member->member.getGroupeaseUser().getId().equals(currentUser.getId())))
        {
            throw new NotGroupMemberException();
        }

        // Update the group
        dataAccess.beginTransaction();
        existingGroup.setName(updateGroup.name);
        existingGroup.setDescription(updateGroup.description);
        dataAccess.commitTransaction();

        return existingGroup;
    }

    // Helper method that ensures that the logged on user is a channel member and therefore has permission
    // to perform operations on group objects.
    private void verifyCurrentUserIsChannelMember(long channelId)
    {
        currentUser = dataAccess.userProfile().getByProviderId(currentUserIdProvider.get());
        if(currentUser == null)
        {
            // No profile for the user was found in the database so can't possibly be a channel member
            // Throwing specific user not found exception. Should consider whether it would just be better
            // to create a profile and then throwing the more general not channel member exception
            throw new UserNotFoundException("There is no profile found for the current user");
        }

        if(currentUser.getMemberList().stream().noneMatch(member -> member.getChannel().getId() == channelId))
        {
            throw new NotChannelMemberException();
        }
    }
}
