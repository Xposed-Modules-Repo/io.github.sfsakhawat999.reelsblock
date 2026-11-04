## Summary

<!-- What does this PR change and why? -->

## Testing

<!-- How did you verify it on a real device? Include app versions. -->

- Device / Android:
- Framework:
- YouTube / Instagram version:
- Tested scenario:

## Checklist

- [ ] `./gradlew assembleDebug` succeeds
- [ ] No Xposed API classes bundled (`dexdump | grep Lde/robv` prints nothing)
- [ ] `META-INF/xposed` metadata is intact
- [ ] Hooks are defensive (no crash paths introduced)
- [ ] README updated if user-visible behavior changed
