# Tag Creation Documentation

## Tag Created

**Tag Name:** v0.9.0  
**Commit:** 7c82189265d8e7cbee5356e8304ca9f378d1a94e  
**Commit Message:** Update base version to 0.9.0 (#30)  
**Tag Message:** Release version 0.9.0  

## Rationale

The commit 7c82189265d8e7cbee5356e8304ca9f378d1a94e updated the base version in `build.gradle.kts` to 0.9.0, indicating a new release version. Following semantic versioning conventions, the tag `v0.9.0` was created to mark this release.

## Commands Used

```bash
git tag -a v0.9.0 7c82189265d8e7cbee5356e8304ca9f378d1a94e -m "Release version 0.9.0"
```

## Next Steps

To push the tag to the remote repository, run:

```bash
git push origin v0.9.0
```

## Verification

The tag can be verified locally with:

```bash
git tag -l
git show v0.9.0
```
