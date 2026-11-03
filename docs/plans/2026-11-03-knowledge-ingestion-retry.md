# Knowledge ingestion retry

## Goal and constraints

Make a failed policy ingestion recoverable from the admin knowledge workbench without requiring the administrator to upload the same file again. Preserve tenant scoping, admin-only access, existing status transitions, and idempotent chunk ingestion.

## Review finding

Failed ingestion can currently be requeued only as a side effect of uploading an identical file again. There is no direct retry endpoint or workbench action, so transient embedding or storage failures add unnecessary operator work.

## Likely files

- `backend/src/main/java/com/aftercare/knowledge/DocumentService.java`
- `backend/src/main/java/com/aftercare/knowledge/KnowledgeController.java`
- `backend/src/main/java/com/aftercare/knowledge/KnowledgeRepository.java`
- `backend/src/test/java/com/aftercare/knowledge/KnowledgeWorkflowIT.java`
- `frontend/src/api/knowledge.ts`
- `frontend/src/views/KnowledgeView.vue`
- Relevant frontend unit/component tests if the existing setup supports them.

## Task order

1. Add an admin-only `POST /api/v1/knowledge/versions/{versionId}/retry` operation. It must verify tenant ownership and `FAILED` status, reset the version and failed outbox job transactionally, and return the updated version.
2. Add an admin workbench retry action for failed versions, with pending/error feedback and status refresh.
3. Add backend regression coverage for retry success, non-failed state rejection, and tenant isolation; add frontend coverage if an existing test seam is available.
4. Run the focused backend integration test and frontend checks relevant to the changed view; review the final diff and commit/push on a feature branch with a PR.

## Acceptance criteria

- An admin can retry a failed policy version without sending the file again.
- Other tenants cannot retry the version; non-failed versions are not silently reset.
- A successful retry restores `UPLOADED`, clears stale chunks/failure reason, and makes the ingestion job claimable.
- The workbench displays current processing state and errors after retry.

## Risks and decisions

- The existing repository method already resets the failed version and outbox row; implementation should reuse it while ensuring state validation and returned state are reliable.
- No material product decision remains open.
