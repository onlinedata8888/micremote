"""Patches the decoded original APK: hooks TvBridge into MainActivity, adds Wi-Fi permissions."""
import sys
d = sys.argv[1]
p = d + '/smali_classes3/com/example/tvremote/MainActivity.smali'
s = open(p).read()
old = '''    iget-object v1, p0, Lcom/example/tvremote/MainActivity;->webView:Landroid/webkit/WebView;

    const-string v2, "file:///android_asset/remote.html"'''
assert s.count(old) == 1, "MainActivity.smali layout differs from expected"
s = s.replace(old, '''    iget-object v1, p0, Lcom/example/tvremote/MainActivity;->webView:Landroid/webkit/WebView;

    invoke-static {p0, v1}, Lcom/example/tvremote/TvBridge;->install(Landroid/app/Activity;Landroid/webkit/WebView;)V

    const-string v2, "file:///android_asset/remote.html"''')
s += '''
.method protected onDestroy()V
    .locals 0

    invoke-static {}, Lcom/example/tvremote/TvBridge;->shutdown()V

    invoke-super {p0}, Landroidx/appcompat/app/AppCompatActivity;->onDestroy()V

    return-void
.end method
'''
open(p, 'w').write(s)
m = open(d + '/AndroidManifest.xml').read()
a = '<uses-permission android:name="android.permission.INTERNET"/>'
assert a in m
m = m.replace(a, a + '\n    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"/>\n    <uses-permission android:name="android.permission.ACCESS_WIFI_STATE"/>\n    <uses-permission android:name="android.permission.CHANGE_WIFI_MULTICAST_STATE"/>\n    <uses-permission android:name="android.permission.RECORD_AUDIO"/>')
open(d + '/AndroidManifest.xml', 'w').write(m)
