"""Renames the app's display label (shown under the launcher icon)."""
import sys
d = sys.argv[1]
p = d + '/res/values/strings.xml'
s = open(p).read()
old = '<string name="app_name">TV Remote</string>'
assert old in s, "app_name string not found where expected"
open(p, 'w').write(s.replace(old, '<string name="app_name">MyRemote v2.2</string>'))
