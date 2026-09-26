package com.example.tvremote;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Finds Google/Android TVs on the Wi-Fi with three independent methods (results are merged):
 * 1) Android NsdManager (mDNS), 2) our own raw mDNS query, 3) a port-6466 scan of the local subnet.
 */
final class Discovery {
    static final class Device {
        final String name;
        final String host;

        Device(String name, String host) {
            this.name = name;
            this.host = host;
        }
    }

    interface Callback {
        void onChanged();
    }

    private static final String SERVICE = "_androidtvremote2._tcp.";

    private final Context ctx;
    private final Callback cb;
    private final Map<String, Device> found = new LinkedHashMap<String, Device>(); // by host
    private NsdManager nsd;
    private NsdManager.DiscoveryListener listener;
    private WifiManager.MulticastLock lock;
    private final LinkedList<NsdServiceInfo> resolveQueue = new LinkedList<NsdServiceInfo>();
    private boolean resolving;
    private volatile boolean running;
    private volatile boolean scanning;
    private volatile boolean querying;
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

    Discovery(Context ctx, Callback cb) {
        this.ctx = ctx.getApplicationContext();
        this.cb = cb;
    }

    synchronized List<Device> snapshot() {
        return new ArrayList<Device>(found.values());
    }

    /** A TV whose address the user typed in. */
    void addManual(Device d) {
        synchronized (this) {
            found.put(d.host, d);
        }
        cb.onChanged();
    }

    private static boolean generic(String name) {
        return name.startsWith("Android TV (");
    }

    private void add(Device d) {
        synchronized (this) {
            Device old = found.get(d.host);
            if (old != null && (!generic(old.name) || generic(d.name))) return; // keep the friendlier name
            found.put(d.host, d);
        }
        cb.onChanged();
    }

    void start() {
        if (running) return;
        running = true;
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            lock = wm.createMulticastLock("tvremote-mdns");
            lock.setReferenceCounted(false);
            lock.acquire();
        } catch (Throwable t) {
            TvLog.d("multicast lock: " + t);
        }
        // method 1: NsdManager
        try {
            nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
            listener = new NsdManager.DiscoveryListener() {
                public void onStartDiscoveryFailed(String t, int e) {
                    TvLog.d("nsd start failed " + e);
                }

                public void onStopDiscoveryFailed(String t, int e) {
                }

                public void onDiscoveryStarted(String t) {
                }

                public void onDiscoveryStopped(String t) {
                }

                public void onServiceFound(NsdServiceInfo info) {
                    synchronized (resolveQueue) {
                        resolveQueue.add(info);
                    }
                    resolveNext();
                }

                public void onServiceLost(NsdServiceInfo info) {
                    // TVs drop mDNS in standby; keep them listed so the user can still pick them.
                }
            };
            nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, listener);
        } catch (Throwable t) {
            TvLog.d("nsd: " + t);
        }
        // methods 2 + 3 right away, and again every 8 s while nothing is found
        rescan();
        main.postDelayed(new Runnable() {
            public void run() {
                if (!running) return;
                if (snapshot().isEmpty()) rescan();
                main.postDelayed(this, 8000);
            }
        }, 8000);
    }

    private void resolveNext() {
        final NsdServiceInfo next;
        synchronized (resolveQueue) {
            if (resolving || resolveQueue.isEmpty()) return;
            next = resolveQueue.removeFirst();
            resolving = true;
        }
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                public void onResolveFailed(NsdServiceInfo i, int e) {
                    done();
                }

                public void onServiceResolved(NsdServiceInfo i) {
                    try {
                        InetAddress a = i.getHost();
                        if (a instanceof Inet4Address) add(new Device(i.getServiceName(), a.getHostAddress()));
                    } catch (Throwable ignored) {
                    }
                    done();
                }

                private void done() {
                    synchronized (resolveQueue) {
                        resolving = false;
                    }
                    resolveNext();
                }
            });
        } catch (Throwable t) {
            synchronized (resolveQueue) {
                resolving = false;
            }
        }
    }

    /** Runs the raw mDNS query and the subnet scan (both in the background). */
    void rescan() {
        mdnsQuery();
        subnetScan();
    }

    // ---- method 2: our own mDNS query (answers come back by unicast, so this works without joining the group)
    private void mdnsQuery() {
        if (querying) return;
        querying = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                DatagramSocket s = null;
                try {
                    s = new DatagramSocket();
                    s.setSoTimeout(300);
                    InetAddress group = InetAddress.getByName("224.0.0.251");
                    byte[] q = Mdns.query();
                    byte[] buf = new byte[4096];
                    long end = System.currentTimeMillis() + 4500;
                    long nextSend = 0;
                    while (System.currentTimeMillis() < end && running) {
                        if (System.currentTimeMillis() >= nextSend) {
                            s.send(new DatagramPacket(q, q.length, group, 5353));
                            nextSend = System.currentTimeMillis() + 1000;
                        }
                        try {
                            DatagramPacket p = new DatagramPacket(buf, buf.length);
                            s.receive(p);
                            String inst = Mdns.parseInstance(p.getData(), p.getLength());
                            if (inst != null && p.getAddress() instanceof Inet4Address) {
                                String ip = p.getAddress().getHostAddress();
                                add(new Device(inst.length() > 0 ? inst : "Android TV (" + ip + ")", ip));
                            }
                        } catch (SocketTimeoutException ignored) {
                        }
                    }
                } catch (Throwable e) {
                    TvLog.d("mdns query: " + e);
                } finally {
                    if (s != null) s.close();
                    querying = false;
                }
            }
        }, "tv-mdns");
        t.setDaemon(true);
        t.start();
    }

    // ---- method 3: probe every address of the local /24 on the remote-control port
    private void subnetScan() {
        if (scanning) return;
        scanning = true;
        final List<String> prefixes = localPrefixes();
        if (prefixes.isEmpty()) {
            scanning = false;
            return;
        }
        final ExecutorService pool = Executors.newFixedThreadPool(48, new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "tv-scan");
                t.setDaemon(true);
                return t;
            }
        });
        for (int p = 0; p < prefixes.size() && p < 2; p++) {
            final String base = prefixes.get(p);
            for (int i = 1; i < 255; i++) {
                final String ip = base + i;
                pool.execute(new Runnable() {
                    public void run() {
                        Socket s = new Socket();
                        try {
                            s.connect(new InetSocketAddress(ip, 6466), 500);
                            add(new Device("Android TV (" + ip + ")", ip));
                        } catch (Throwable ignored) {
                        } finally {
                            try {
                                s.close();
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
            }
        }
        pool.shutdown();
        new Thread(new Runnable() {
            public void run() {
                try {
                    pool.awaitTermination(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }
                scanning = false;
            }
        }, "tv-scan-wait").start();
    }

    private static String prefixOf(InetAddress a) {
        String h = a.getHostAddress();
        return h.substring(0, h.lastIndexOf('.') + 1);
    }

    private List<String> localPrefixes() {
        Set<String> out = new LinkedHashSet<String>();
        // 1) the active network's addresses (most reliable on new Android versions)
        try {
            ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= 23) {
                Network n = cm.getActiveNetwork();
                LinkProperties lp = n == null ? null : cm.getLinkProperties(n);
                if (lp != null) {
                    for (LinkAddress la : lp.getLinkAddresses()) {
                        InetAddress a = la.getAddress();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) out.add(prefixOf(a));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        // 2) Wi-Fi manager
        try {
            WifiManager wm = (WifiManager) ctx.getSystemService(Context.WIFI_SERVICE);
            int ip = wm.getConnectionInfo().getIpAddress();
            if (ip != 0) out.add((ip & 0xFF) + "." + ((ip >> 8) & 0xFF) + "." + ((ip >> 16) & 0xFF) + ".");
        } catch (Throwable ignored) {
        }
        // 3) network interfaces
        try {
            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            if (nis != null) {
                for (NetworkInterface ni : Collections.list(nis)) {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                    for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                        if (a instanceof Inet4Address && a.isSiteLocalAddress()) out.add(prefixOf(a));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return new ArrayList<String>(out);
    }

    void stop() {
        running = false;
        try {
            if (nsd != null && listener != null) nsd.stopServiceDiscovery(listener);
        } catch (Throwable ignored) {
        }
        listener = null;
        try {
            if (lock != null && lock.isHeld()) lock.release();
        } catch (Throwable ignored) {
        }
    }
}
