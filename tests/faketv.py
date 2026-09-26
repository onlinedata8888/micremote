"""Fake Google TV built on the reference library's protobuf schema (independent of my Java encoder)."""
import importlib.util, ssl, socket, threading, hashlib, sys, json, time, datetime
sys.path.insert(0, '/home/claude/tools/atv')
def load(n):
    s = importlib.util.spec_from_file_location(n, f'/home/claude/tools/atv/androidtvremote2/{n}.py'); m = importlib.util.module_from_spec(s); s.loader.exec_module(m); return m
polo = load('polo_pb2'); rm = load('remotemessage_pb2')
from google.protobuf.internal.decoder import _DecodeVarint
from google.protobuf.internal.encoder import _EncodeVarint
from cryptography import x509
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa
from cryptography.x509.oid import NameOID

ident = sys.argv[1]; PP, RP = int(sys.argv[2]), int(sys.argv[3]); codefile = sys.argv[4]; logfile = sys.argv[5]
LOG = []
def log(*a): LOG.append(list(map(str, a))); json.dump(LOG, open(logfile, 'w'))

# server identity
key = rsa.generate_private_key(65537, 2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, 'faketv')])
now = datetime.datetime.now(datetime.timezone.utc)
cert = x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key()).serial_number(1).not_valid_before(now - datetime.timedelta(days=1)).not_valid_after(now + datetime.timedelta(days=365)).sign(key, hashes.SHA256())
open('/tmp/srv.pem', 'wb').write(cert.public_bytes(serialization.Encoding.PEM))
open('/tmp/srv.key', 'wb').write(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.TraditionalOpenSSL, serialization.NoEncryption()))

def ctx():
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    c.load_cert_chain('/tmp/srv.pem', '/tmp/srv.key')
    c.verify_mode = ssl.CERT_REQUIRED
    c.load_verify_locations(ident + '/client.pem')   # our phone's self-signed cert is its own trust anchor
    return c

def read_frame(f):
    n = 0; shift = 0
    while True:
        b = f.read(1)
        if not b: raise EOFError
        n |= (b[0] & 0x7f) << shift
        if not b[0] & 0x80: break
        shift += 7
    data = b''
    while len(data) < n:
        c = f.read(n - len(data))
        if not c: raise EOFError
        data += c
    return data
def send(sock, msg):
    _EncodeVarint(sock.sendall, msg.ByteSize()); sock.sendall(msg.SerializeToString())

def nums(cert_der):
    k = x509.load_der_x509_certificate(cert_der).public_key().public_numbers(); return k.n, k.e

def pairing_server():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(('127.0.0.1', PP)); s.listen(5)
    c = ctx()
    while True:
        raw, _ = s.accept()
        try:
            t = c.wrap_socket(raw, server_side=True); f = t.makefile('rb')
            cn, ce = nums(t.getpeercert(True)); sn, se = nums(cert.public_bytes(serialization.Encoding.DER))
            nonce = bytes.fromhex('BEEF')
            h = hashlib.sha256()
            for v in (cn, ce, sn, se):
                pass
            h.update(bytes.fromhex(f"{cn:X}")); h.update(bytes.fromhex(f"0{ce:X}")); h.update(bytes.fromhex(f"{sn:X}")); h.update(bytes.fromhex(f"0{se:X}")); h.update(nonce)
            want = h.digest(); code = f"{want[0]:02X}BEEF"
            while True:
                m = polo.OuterMessage(); m.ParseFromString(read_frame(f))
                out = polo.OuterMessage(); out.protocol_version = 2; out.status = 200
                if m.HasField('pairing_request'):
                    log('pairing_request', m.pairing_request.service_name, m.pairing_request.client_name, 'pv', m.protocol_version)
                    out.pairing_request_ack.server_name = 'FakeTV'
                elif m.HasField('options'):
                    log('options role', m.options.preferred_role, 'enc', [(e.type, e.symbol_length) for e in m.options.input_encodings])
                    out.options.preferred_role = 2
                    e = out.options.input_encodings.add(); e.type = 3; e.symbol_length = 6
                elif m.HasField('configuration'):
                    log('configuration role', m.configuration.client_role, 'enc', m.configuration.encoding.type, m.configuration.encoding.symbol_length)
                    out.configuration_ack.SetInParent()
                    send(t, out); open(codefile, 'w').write(code); continue
                elif m.HasField('secret'):
                    ok = m.secret.secret == want
                    log('secret_ok', ok)
                    if ok: out.secret_ack.secret = want
                    else: out.status = 402
                    send(t, out); break
                else:
                    log('pairing unexpected'); break
                send(t, out)
            t.close()
        except Exception as e:
            log('pairing err', repr(e))

def remote_server():
    s = socket.socket(); s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1); s.bind(('127.0.0.1', RP)); s.listen(5)
    c = ctx()
    while True:
        raw, _ = s.accept()
        try:
            t = c.wrap_socket(raw, server_side=True); f = t.makefile('rb')
            m = rm.RemoteMessage(); m.remote_configure.code1 = 622; m.remote_configure.device_info.model = 'FakeTV'; m.remote_configure.device_info.vendor = 'Test'; send(t, m)
            r = rm.RemoteMessage(); r.ParseFromString(read_frame(f)); log('cfg_reply code1', r.remote_configure.code1, 'pkg', r.remote_configure.device_info.package_name, 'u1', r.remote_configure.device_info.unknown1, 'u2', r.remote_configure.device_info.unknown2, 'ver', r.remote_configure.device_info.app_version)
            m = rm.RemoteMessage(); m.remote_set_active.active = 622; send(t, m)
            r = rm.RemoteMessage(); r.ParseFromString(read_frame(f)); log('set_active_reply', r.remote_set_active.active)
            m = rm.RemoteMessage(); m.remote_start.started = True; send(t, m)
            m = rm.RemoteMessage(); m.remote_set_volume_level.volume_max = 100; m.remote_set_volume_level.volume_level = 20; m.remote_set_volume_level.volume_muted = False; send(t, m)
            m = rm.RemoteMessage(); m.remote_ime_batch_edit.ime_counter = 3; m.remote_ime_batch_edit.field_counter = 5; send(t, m)
            m = rm.RemoteMessage(); m.remote_ping_request.val1 = 7; send(t, m)
            while True:
                r = rm.RemoteMessage(); r.ParseFromString(read_frame(f))
                if r.HasField('remote_ping_response'): log('ping_response', r.remote_ping_response.val1)
                elif r.HasField('remote_key_inject'):
                    log('key', r.remote_key_inject.key_code, rm.RemoteDirection.Name(r.remote_key_inject.direction))
                    if r.remote_key_inject.key_code == 84:   # SEARCH -> TV opens a voice session
                        vm = rm.RemoteMessage(); vm.remote_voice_begin.session_id = 4242; vm.remote_voice_begin.package_name = 'com.google.android.katniss'; send(t, vm)
                elif r.HasField('remote_voice_begin'): log('voice_begin_echo', r.remote_voice_begin.session_id)
                elif r.HasField('remote_voice_payload'):
                    log('voice_payload', r.remote_voice_payload.session_id, len(r.remote_voice_payload.samples))
                    globals()['NPAY'] = globals().get('NPAY', 0) + 1
                    if NPAY == 2:   # TV decides it has heard enough and ends the session by itself
                        em = rm.RemoteMessage(); em.remote_voice_end.session_id = r.remote_voice_payload.session_id; send(t, em)
                elif r.HasField('remote_voice_end'): log('voice_end', r.remote_voice_end.session_id)
                elif r.HasField('remote_ime_batch_edit'):
                    b = r.remote_ime_batch_edit; e = b.edit_info[0]
                    log('ime', b.ime_counter, b.field_counter, e.insert, e.text_field_status.start, e.text_field_status.end, e.text_field_status.value)
                elif r.HasField('remote_app_link_launch_request'): log('launch', r.remote_app_link_launch_request.app_link)
                else: log('remote other', str(r))
        except EOFError:
            log('remote closed')
        except Exception as e:
            log('remote err', repr(e))

threading.Thread(target=pairing_server, daemon=True).start()
threading.Thread(target=remote_server, daemon=True).start()
print('fake TV up', flush=True)
time.sleep(60)
