package com.app.modules.post.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.MalformedBodyErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.error.BannedHashtagErrorResponse;
import com.app.modules.post.dto.request.ReplaceCollaboratorsRequest;
import com.app.modules.post.dto.request.ReplacePostTagsRequest;
import com.app.modules.post.dto.request.SetPostPlaceRequest;
import com.app.modules.post.dto.request.UpdateAltTextRequest;
import com.app.modules.post.dto.request.UpdatePostContentRequest;
import com.app.modules.post.dto.response.PostResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the parts of a post the owner edits after creating it: the content of a
 * draft, alt text, place, people tags and co-authors, and the answers of the people involved.
 */
@Tag(
        name = "Post Creation",
        description =
                "Drafts, alt text, places, people tags and collaborative posts on a post the"
                        + " caller owns or is involved in")
@RequestMapping(ApiConstants.Posts.ROOT)
public interface PostCreationApi {

    @Operation(
            summary = "Replace the content of a draft or scheduled post",
            description =
                    "Replaces the caption, media with alt text, place, people tags and invited"
                            + " co-authors of a draft or scheduled post. It is a replacement, not"
                            + " a patch: what the request leaves out is cleared. The status and the"
                            + " scheduled time do not change. A published or archived post cannot"
                            + " be replaced; use the caption, place, tag and alt text edits.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The post after the replacement"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The post is published and the caller is not its owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such post, or a media asset that is not found",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "POST_STATE_CONFLICT: the post is not a draft or scheduled post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description =
                        "The caption names a banned hashtag (the payload lists them in"
                                + " data.bannedTags), or the place is no longer available, a"
                                + " tagged person does not allow tags, or a limit on tags or"
                                + " co-authors is reached",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema =
                                        @Schema(
                                                implementation =
                                                        BannedHashtagErrorResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PutMapping(ApiConstants.Posts.CONTENT)
    ResponseEntity<ApiResponse<PostResponse>> replaceContent(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody UpdatePostContentRequest request);

    @Operation(
            summary = "Edit the alt text of one media item",
            description =
                    "Sets or clears the alt text of a media item of an owned post in any status"
                            + " the owner still holds. At most 500 characters; blank clears it,"
                            + " and readers then get a label generated by the client.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The post after the change"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The post is published and the caller is not its owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such post, or the media item is not part of the post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PatchMapping(ApiConstants.Posts.ALT_TEXT)
    ResponseEntity<ApiResponse<PostResponse>> updateAltText(
            @PathVariable("postId") UUID postId,
            @PathVariable("postMediaId") UUID postMediaId,
            @Valid @RequestBody UpdateAltTextRequest request);

    @Operation(
            summary = "Set or clear the place of a post",
            description =
                    "Tags an owned post at a place chosen by id or typed by name, creating the"
                            + " place when the name is new. Neither clears the place. A place an"
                            + " administrator removed is refused.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The post after the change"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The post is published and the caller is not its owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such post or place",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "POST_PLACE_UNAVAILABLE: the place was banned or deleted",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PutMapping(ApiConstants.Posts.PLACE)
    ResponseEntity<ApiResponse<PostResponse>> setPlace(
            @PathVariable("postId") UUID postId, @Valid @RequestBody SetPostPlaceRequest request);

    @Operation(
            summary = "Replace the people tagged on a post",
            description =
                    "Sets the complete tag set of an owned post. A tag already present keeps its"
                            + " approval; a new tag is approved at once, waits for the tagged"
                            + " person, or waits without telling them, according to that person's"
                            + " settings and limits. A person who blocked the caller, or whom the"
                            + " caller blocked, answers as a user that does not exist.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The post after the change"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The post is published and the caller is not its owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such post or tagged person",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description =
                        "POST_TAG_NOT_ALLOWED: the person does not allow tags from the caller;"
                                + " POST_TAG_LIMIT_REACHED: the item already holds the maximum",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PutMapping(ApiConstants.Posts.TAGS)
    ResponseEntity<ApiResponse<PostResponse>> replaceTags(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody ReplacePostTagsRequest request);

    @Operation(
            summary = "Approve a tag of the caller",
            description =
                    "The tagged person accepts a pending tag, which then shows to everyone who can"
                            + " see the post. Approving an approved tag changes nothing.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Tag approved, or already approved"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_TAG_NOT_FOUND: no such tag, it is not the caller's, or the post is"
                                + " not visible to the caller",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Posts.TAG_APPROVE)
    ResponseEntity<Void> approveTag(
            @PathVariable("postId") UUID postId, @PathVariable("tagId") UUID tagId);

    @Operation(
            summary = "Remove a tag",
            description =
                    "The post owner or the tagged person removes a tag at any time, whether it is"
                            + " pending or approved.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Tag removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_TAG_NOT_FOUND: no such tag, or the caller is neither the owner nor"
                                + " the tagged person",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Posts.TAG_BY_ID)
    ResponseEntity<Void> removeTag(
            @PathVariable("postId") UUID postId, @PathVariable("tagId") UUID tagId);

    @Operation(
            summary = "Replace the co-authors invited to a post",
            description =
                    "Sets the complete set of accounts holding a live invitation or co-authorship,"
                            + " at most three. An account left out is withdrawn or removed. An"
                            + " invitation is announced when the post is published, or at once on a"
                            + " post that already is. A person blocked with the caller answers as a"
                            + " user that does not exist.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "The post after the change"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "The post is published and the caller is not its owner",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such post or account",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "422",
                description = "POST_COLLABORATOR_LIMIT_REACHED: more than three co-authors",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @MalformedBodyErrorResponses
    @PutMapping(ApiConstants.Posts.COLLABORATORS)
    ResponseEntity<ApiResponse<PostResponse>> replaceCollaborators(
            @PathVariable("postId") UUID postId,
            @Valid @RequestBody ReplaceCollaboratorsRequest request);

    @Operation(
            summary = "Accept an invitation to co-author a post",
            description =
                    "The invited account becomes a co-author, shown on the post and listed on its"
                            + " own profile. Only possible while the post is published.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Invitation accepted"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_COLLABORATION_NOT_FOUND: there is no open invitation for the caller"
                                + " on a published post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Posts.COLLABORATION_ACCEPT)
    ResponseEntity<Void> acceptCollaboration(@PathVariable("postId") UUID postId);

    @Operation(
            summary = "Decline an invitation to co-author a post",
            description = "The invited account turns the invitation down.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Invitation declined"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_COLLABORATION_NOT_FOUND: there is no open invitation for the caller"
                                + " on a published post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @PostMapping(ApiConstants.Posts.COLLABORATION_DECLINE)
    ResponseEntity<Void> declineCollaboration(@PathVariable("postId") UUID postId);

    @Operation(
            summary = "Leave a post the caller co-authors",
            description =
                    "A co-author gives up the co-authorship. The post leaves the caller's profile;"
                            + " it stays with the owner.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Co-authorship ended"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description =
                        "POST_COLLABORATION_NOT_FOUND: the caller is not a co-author of the post",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "429",
                description = "Rate limit exceeded",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class)))
    })
    @AuthenticationRequiredResponse
    @DeleteMapping(ApiConstants.Posts.COLLABORATION)
    ResponseEntity<Void> leaveCollaboration(@PathVariable("postId") UUID postId);
}
