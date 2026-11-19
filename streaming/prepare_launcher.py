#!/usr/bin/env python3
"""Build-time branding and update isolation. Run only in a disposable build checkout."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
for rel in ['frontend/Sources/NXCore/ReleaseSource.swift','frontend/Sources/NXCore/EngineUpdater.swift','frontend/Sources/NXModel/RepoStore.swift','frontend/Tests/NX2AppleTests/EngineUpdaterTests.swift']:
    p=root/rel;s=p.read_text()
    if 'LNPANDY/NX2Apple' not in s: raise SystemExit(f'Update endpoint anchor changed: {rel}')
    p.write_text(s.replace('LNPANDY/NX2Apple','xuqian-git/NX2Apple'))
p=root/'frontend/Sources/NX2Apple/NX2AppleApp.swift';s=p.read_text();anchor='    static func main() {\n'
if s.count(anchor)!=1: raise SystemExit('Entry point anchor changed')
s=s.replace(anchor,anchor+'''        // NXStream prototypes always use their bundled, overlaid engine.
        // The distinct bundle identifier keeps these preferences separate from upstream.
        if let url = Bundle.main.resourceURL?.appendingPathComponent("engine/.engine-id"),
           let id = try? String(contentsOf: url, encoding: .utf8) {
            UserDefaults.standard.set(id.trimmingCharacters(in: .whitespacesAndNewlines), forKey: "engineID")
            UserDefaults.standard.set("stable", forKey: "engineChannel")
            UserDefaults.standard.set(false, forKey: "autoUpdateRepo")
            UserDefaults.standard.set(false, forKey: "autoUpdateApp")
        }
''');p.write_text(s)
# The shared shell declares its discovery services and local-network usage in its project template.
