package sh.libre.scim.core;

import com.fasterxml.jackson.databind.JsonNode;

import de.captaingoldfish.scim.sdk.common.constants.AttributeNames;
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.request.PatchRequestOperation;

/**
 * Builds PATCH operations whose {@code value} keeps its JSON type.
 *
 * <p>The SDK's {@code valueNode} wraps every node in an array, so a boolean
 * goes out as {@code [true]}. RFC 7643 defines single-valued attributes such
 * as {@code active} as a scalar, and a strict server rejects the array. This
 * helper sets the value node as given.
 */
final class ScimPatchOps {

    private ScimPatchOps() {
    }

    /** A REPLACE operation on {@code path} with {@code value} sent as is. */
    static PatchRequestOperation replace(String path, JsonNode value) {
        var op = PatchRequestOperation.builder().path(path).op(PatchOp.REPLACE).build();
        op.set(AttributeNames.RFC7643.VALUE, value);
        return op;
    }
}
