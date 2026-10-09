package com.app.modules.admin.api;

import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import com.app.common.ApiConstants;
import com.app.common.config.openapi.AuthenticationRequiredResponse;
import com.app.common.config.openapi.CursorErrorResponses;
import com.app.common.response.ApiResponse;
import com.app.common.response.CursorPageResponse;
import com.app.modules.admin.dto.request.AdminDeleteHiddenWordRequest;
import com.app.modules.admin.dto.request.AdminHiddenWordRequest;
import com.app.modules.social.dto.response.AdminHiddenWordResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * OpenAPI contract for the administrator-managed platform-wide hidden word list.
 *
 * <p>Every operation requires the administrator role. A comment or message request that matches an
 * entry is hidden from its recipient unless that account turned the platform list off, so a change
 * here changes what a whole audience is shown and is audited on every write.
 */
@Tag(name = "Administration", description = "Moderation actions and immutable audit history")
@RequestMapping(ApiConstants.Admin.ROOT)
public interface AdminHiddenWordApi {

    /** Lists the platform-wide hidden words newest first. */
    @Operation(
            summary = "List hidden words",
            description =
                    "Returns one cursor page of the platform-wide hidden word list ordered by"
                            + " creation time descending. The optional search term is normalised"
                            + " like a phrase, so case and accents are ignored, and matches any"
                            + " entry containing it.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Cursor page of hidden words"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
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
    @CursorErrorResponses
    @GetMapping(ApiConstants.Admin.HIDDEN_WORDS)
    ResponseEntity<ApiResponse<CursorPageResponse<AdminHiddenWordResponse>>> listHiddenWords(
            @Parameter(description = "Text an entry must contain; case and accents are ignored")
                    @RequestParam(name = "q", required = false)
                    String query,
            @Parameter(description = "Opaque cursor from a previous page")
                    @RequestParam(required = false)
                    String cursor,
            @Parameter(description = "Page size")
                    @RequestParam(defaultValue = "20")
                    @Min(1)
                    @Max(100)
                    int limit);

    /** Adds a phrase to the platform-wide list. */
    @Operation(
            summary = "Create a hidden word",
            description =
                    "Adds a word or phrase to the platform-wide list. It takes effect for new"
                            + " comments and message requests as soon as the request returns. The"
                            + " phrase obeys the same bounds as a personal entry: 1 to 64"
                            + " characters and 1 to 6 words after normalisation, with no control"
                            + " characters. Writes one create_hidden_word audit row.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "201",
                description = "Hidden word created"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description =
                        "VALIDATION_ERROR when the reason is missing, or HIDDEN_WORD_INVALID when"
                                + " the phrase is missing, too long, has no letters or digits, has"
                                + " more than 6 words or contains a control character",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description =
                        "HIDDEN_WORD_DUPLICATE: an entry with the same normalised form exists",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
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
    @PostMapping(ApiConstants.Admin.HIDDEN_WORDS)
    ResponseEntity<ApiResponse<AdminHiddenWordResponse>> createHiddenWord(
            @Valid @RequestBody AdminHiddenWordRequest request);

    /** Replaces the phrase of a platform-wide hidden word. */
    @Operation(
            summary = "Edit a hidden word",
            description =
                    "Replaces the phrase of one entry. Re-typing an entry with different case or"
                            + " accents is accepted. Writes one edit_hidden_word audit row that"
                            + " carries the phrase it replaced.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "200",
                description = "Hidden word updated"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR or HIDDEN_WORD_INVALID, as for creation",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such hidden word",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "409",
                description = "HIDDEN_WORD_DUPLICATE: another entry has the same normalised form",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
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
    @PatchMapping(ApiConstants.Admin.HIDDEN_WORD_BY_ID)
    ResponseEntity<ApiResponse<AdminHiddenWordResponse>> updateHiddenWord(
            @PathVariable("hiddenWordId") UUID hiddenWordId,
            @Valid @RequestBody AdminHiddenWordRequest request);

    /** Removes a phrase from the platform-wide list. */
    @Operation(
            summary = "Delete a hidden word",
            description =
                    "Removes one entry from the platform-wide list. A hard delete of a"
                            + " configuration row, not of user content: the phrase survives only in"
                            + " the delete_hidden_word audit row's metadata.")
    @ApiResponses({
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "204",
                description = "Hidden word removed"),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "400",
                description = "VALIDATION_ERROR when the reason is missing",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "403",
                description = "Administrator role required",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "404",
                description = "No such hidden word",
                content =
                        @Content(
                                mediaType = "application/json",
                                schema = @Schema(implementation = ApiResponse.class))),
        @io.swagger.v3.oas.annotations.responses.ApiResponse(
                responseCode = "415",
                description = "Request body was sent with an unsupported Content-Type",
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
    @DeleteMapping(ApiConstants.Admin.HIDDEN_WORD_BY_ID)
    ResponseEntity<Void> deleteHiddenWord(
            @PathVariable("hiddenWordId") UUID hiddenWordId,
            @Valid @RequestBody AdminDeleteHiddenWordRequest request);
}
