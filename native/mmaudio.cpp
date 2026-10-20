// MultiMedia – Ton-Helfer für Windows: nimmt den Ton des PCs auf (ohne Minecraft selbst) und schreibt
// ihn als Roh-Audio (48 kHz, 16 Bit, Stereo) nach stdout. ffmpeg packt ihn dann zum Bild.
//
//   mmaudio.exe all <minecraftPid>                 alles außer Minecraft
//   mmaudio.exe except <minecraftPid> <a.exe,b.exe> alles außer Minecraft und diesen Programmen
//   mmaudio.exe only <minecraftPid> <a.exe,b.exe>   nur diese Programme
//   mmaudio.exe list <minecraftPid>                Programme, die gerade Ton haben (eins pro Zeile)
//
// Bau: x86_64-w64-mingw32-g++ -O2 -static -municode mmaudio.cpp -o mmaudio.exe -lole32 -luuid -lmmdevapi
#define WIN32_LEAN_AND_MEAN
#define INITGUID
#include <windows.h>
#include <initguid.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <audiopolicy.h>
#include <tlhelp32.h>
#include <mmsystem.h>
#include <io.h>
#include <fcntl.h>
#include <stdio.h>
#include <atomic>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <string>
#include <thread>
#include <vector>
#include <algorithm>

// --- Prozess-Loopback (Windows 10 2004+); fehlt in den mingw-Headern
enum MM_ACTIVATION_TYPE { MM_ACTIVATION_DEFAULT = 0, MM_ACTIVATION_PROCESS_LOOPBACK = 1 };
enum MM_LOOPBACK_MODE { MM_INCLUDE_TREE = 0, MM_EXCLUDE_TREE = 1 };
struct MM_LOOPBACK_PARAMS { DWORD TargetProcessId; MM_LOOPBACK_MODE Mode; };
struct MM_ACTIVATION_PARAMS { MM_ACTIVATION_TYPE ActivationType; MM_LOOPBACK_PARAMS ProcessLoopbackParams; };
static const wchar_t *PROCESS_LOOPBACK_DEVICE = L"VAD\\Process_Loopback";
DEFINE_GUID(MM_KSDATAFORMAT_SUBTYPE_IEEE_FLOAT, 0x00000003, 0x0000, 0x0010, 0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71);
DEFINE_GUID(MM_IID_IAgileObject, 0x94ea2b94, 0xe9cc, 0x49e0, 0xc0, 0xff, 0xee, 0x64, 0xca, 0x8f, 0x5b, 0x90);

static const int RATE = 48000;
static const int CH = 2;
static const size_t MAX_BACKLOG = RATE * CH * 3 / 10; // 300 ms – mehr wird verworfen (geringe Verzögerung)

static void logf(const char *fmt, ...) {
	va_list a;
	va_start(a, fmt);
	vfprintf(stderr, fmt, a);
	fputc('\n', stderr);
	fflush(stderr);
	va_end(a);
}

static std::wstring lower(std::wstring s) {
	for (auto &c : s) c = towlower(c);
	return s;
}

static std::string utf8(const std::wstring &w) {
	if (w.empty()) return {};
	int n = WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), nullptr, 0, nullptr, nullptr);
	std::string s(n, 0);
	WideCharToMultiByte(CP_UTF8, 0, w.c_str(), (int)w.size(), &s[0], n, nullptr, nullptr);
	return s;
}

static std::wstring exeName(DWORD pid) {
	HANDLE h = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, FALSE, pid);
	if (!h) return {};
	wchar_t buf[MAX_PATH];
	DWORD len = MAX_PATH;
	std::wstring name;
	if (QueryFullProcessImageNameW(h, 0, buf, &len)) {
		name.assign(buf, len);
		size_t slash = name.find_last_of(L"\\/");
		if (slash != std::wstring::npos) name = name.substr(slash + 1);
	}
	CloseHandle(h);
	return name;
}

static std::map<DWORD, DWORD> parents() {
	std::map<DWORD, DWORD> m;
	HANDLE snap = CreateToolhelp32Snapshot(TH32CS_SNAPPROCESS, 0);
	if (snap == INVALID_HANDLE_VALUE) return m;
	PROCESSENTRY32W pe{};
	pe.dwSize = sizeof(pe);
	if (Process32FirstW(snap, &pe)) {
		do m[pe.th32ProcessID] = pe.th32ParentProcessID; while (Process32NextW(snap, &pe));
	}
	CloseHandle(snap);
	return m;
}

static bool hasAncestor(DWORD pid, const std::set<DWORD> &set, const std::map<DWORD, DWORD> &par) {
	for (int depth = 0; depth < 32; depth++) {
		auto it = par.find(pid);
		if (it == par.end() || it->second == 0 || it->second == pid) return false;
		pid = it->second;
		if (set.count(pid)) return true;
	}
	return false;
}

// ------------------------------------------------------------------ Aufnahme

/** Ein Aufnahme-Kanal (ganzer PC oder ein Prozess-Baum); sammelt Samples in einem Puffer. */
struct Capture {
	std::mutex lock;
	std::vector<int16_t> buf; // verschachtelt L,R
	std::atomic<bool> stop{false};
	std::atomic<bool> dead{false};
	std::thread thread;
	DWORD pid = 0;

	void push(const int16_t *data, size_t samples) {
		std::lock_guard<std::mutex> g(lock);
		buf.insert(buf.end(), data, data + samples);
		if (buf.size() > MAX_BACKLOG) buf.erase(buf.begin(), buf.begin() + (buf.size() - MAX_BACKLOG / 2));
	}
	void pushSilence(size_t samples) {
		std::lock_guard<std::mutex> g(lock);
		buf.insert(buf.end(), samples, 0);
		if (buf.size() > MAX_BACKLOG) buf.erase(buf.begin(), buf.begin() + (buf.size() - MAX_BACKLOG / 2));
	}
	/** Mischt bis zu n Samples dazu; was fehlt, bleibt still. */
	bool primed = false;
	void mixInto(int32_t *out, size_t n) {
		std::lock_guard<std::mutex> g(lock);
		// kleiner Puffer (40 ms), damit ungleichmäßig ankommende Pakete keine Knackser machen
		if (!primed && buf.size() < (size_t)RATE * CH / 25) return;
		primed = buf.size() >= n;
		size_t k = std::min(n, buf.size());
		for (size_t i = 0; i < k; i++) out[i] += buf[i];
		buf.erase(buf.begin(), buf.begin() + k);
	}
	~Capture() {
		stop = true;
		if (thread.joinable()) thread.join();
	}
};

struct Handler : public IActivateAudioInterfaceCompletionHandler {
	LONG refs = 1;
	HANDLE done = CreateEventW(nullptr, TRUE, FALSE, nullptr);
	HRESULT hr = E_FAIL;
	IAudioClient *client = nullptr;

	HRESULT STDMETHODCALLTYPE QueryInterface(REFIID riid, void **out) override {
		if (riid == IID_IUnknown || riid == IID_IActivateAudioInterfaceCompletionHandler || riid == MM_IID_IAgileObject) {
			*out = this;
			AddRef();
			return S_OK;
		}
		*out = nullptr;
		return E_NOINTERFACE;
	}
	ULONG STDMETHODCALLTYPE AddRef() override { return InterlockedIncrement(&refs); }
	ULONG STDMETHODCALLTYPE Release() override {
		LONG r = InterlockedDecrement(&refs);
		if (r == 0) delete this;
		return r;
	}
	HRESULT STDMETHODCALLTYPE ActivateCompleted(IActivateAudioInterfaceAsyncOperation *op) override {
		HRESULT activateHr = E_FAIL;
		IUnknown *unk = nullptr;
		hr = op->GetActivateResult(&activateHr, &unk);
		if (SUCCEEDED(hr)) hr = activateHr;
		if (SUCCEEDED(hr) && unk) hr = unk->QueryInterface(IID_IAudioClient, (void **)&client);
		if (unk) unk->Release();
		SetEvent(done);
		return S_OK;
	}
};

typedef HRESULT(WINAPI *ActivateFn)(LPCWSTR, REFIID, PROPVARIANT *, IActivateAudioInterfaceCompletionHandler *,
		IActivateAudioInterfaceAsyncOperation **);

/** Prozess-Loopback-Client (Einschluss oder Ausschluss eines Prozess-Baums). */
static IAudioClient *activateProcessLoopback(DWORD pid, bool include) {
	static ActivateFn fn = (ActivateFn)GetProcAddress(LoadLibraryW(L"Mmdevapi.dll"), "ActivateAudioInterfaceAsync");
	if (!fn) return nullptr;
	MM_ACTIVATION_PARAMS params{};
	params.ActivationType = MM_ACTIVATION_PROCESS_LOOPBACK;
	params.ProcessLoopbackParams.TargetProcessId = pid;
	params.ProcessLoopbackParams.Mode = include ? MM_INCLUDE_TREE : MM_EXCLUDE_TREE;
	PROPVARIANT pv;
	PropVariantInit(&pv);
	pv.vt = VT_BLOB;
	pv.blob.cbSize = sizeof(params);
	pv.blob.pBlobData = (BYTE *)&params;
	Handler *h = new Handler();
	IActivateAudioInterfaceAsyncOperation *op = nullptr;
	HRESULT hr = fn(PROCESS_LOOPBACK_DEVICE, IID_IAudioClient, &pv, h, &op);
	IAudioClient *client = nullptr;
	if (SUCCEEDED(hr) && WaitForSingleObject(h->done, 5000) == WAIT_OBJECT_0 && SUCCEEDED(h->hr)) {
		client = h->client;
	} else {
		logf("Prozess-Loopback (%lu) ging nicht: 0x%08lx / 0x%08lx", pid, (unsigned long)hr, (unsigned long)h->hr);
		if (h->client) h->client->Release();
	}
	if (op) op->Release();
	h->Release();
	return client;
}

/** Liest einen Client bis zum Stopp. Wandelt Float/andere Kanalzahlen nach 16 Bit Stereo. */
static void captureLoop(Capture *cap, IAudioClient *client, bool processLoopback) {
	CoInitializeEx(nullptr, COINIT_MULTITHREADED);
	WAVEFORMATEX want{};
	want.wFormatTag = WAVE_FORMAT_PCM;
	want.nChannels = CH;
	want.nSamplesPerSec = RATE;
	want.wBitsPerSample = 16;
	want.nBlockAlign = CH * 2;
	want.nAvgBytesPerSec = RATE * want.nBlockAlign;
	WAVEFORMATEX *fmt = &want;
	WAVEFORMATEX *mix = nullptr;
	HRESULT hr;
	DWORD flags = AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK;
	if (processLoopback) {
		hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
				2000000, 0, fmt, nullptr);
		if (FAILED(hr)) hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 2000000, 0, fmt, nullptr);
	} else {
		// klassisches Loopback: im Format des Geräts, wird unten umgewandelt
		hr = client->GetMixFormat(&mix);
		if (SUCCEEDED(hr)) {
			fmt = mix;
			hr = client->Initialize(AUDCLNT_SHAREMODE_SHARED, flags, 2000000, 0, fmt, nullptr);
		}
	}
	if (FAILED(hr)) {
		logf("Aufnahme starten ging nicht: 0x%08lx", (unsigned long)hr);
		cap->dead = true;
		client->Release();
		return;
	}
	bool isFloat = fmt->wFormatTag == WAVE_FORMAT_IEEE_FLOAT
			|| (fmt->wFormatTag == WAVE_FORMAT_EXTENSIBLE && ((WAVEFORMATEXTENSIBLE *)fmt)->SubFormat == MM_KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
	int inCh = fmt->nChannels;
	int inRate = fmt->nSamplesPerSec;
	int bits = fmt->wBitsPerSample;
	HANDLE ev = CreateEventW(nullptr, FALSE, FALSE, nullptr);
	client->SetEventHandle(ev);
	IAudioCaptureClient *cc = nullptr;
	client->GetService(IID_IAudioCaptureClient, (void **)&cc);
	if (!cc || FAILED(client->Start())) {
		logf("Aufnahme-Dienst fehlt");
		cap->dead = true;
		client->Release();
		return;
	}
	std::vector<int16_t> tmp;
	double resamplePos = 0; // einfacher Resampler, falls das Gerät nicht 48 kHz hat
	int16_t lastL = 0, lastR = 0;
	while (!cap->stop) {
		WaitForSingleObject(ev, 100);
		UINT32 packet = 0;
		while (SUCCEEDED(hr = cc->GetNextPacketSize(&packet)) && packet > 0) {
			BYTE *data;
			UINT32 frames;
			DWORD f;
			if (FAILED(hr = cc->GetBuffer(&data, &frames, &f, nullptr, nullptr))) break;
			tmp.clear();
			bool silent = (f & AUDCLNT_BUFFERFLAGS_SILENT) != 0;
			for (UINT32 i = 0; i < frames; i++) {
				float l = 0, r = 0;
				if (!silent) {
					if (isFloat && bits == 32) {
						float *p = (float *)data + (size_t)i * inCh;
						l = p[0];
						r = inCh > 1 ? p[1] : p[0];
					} else if (bits == 16) {
						int16_t *p = (int16_t *)data + (size_t)i * inCh;
						l = p[0] / 32768.0f;
						r = (inCh > 1 ? p[1] : p[0]) / 32768.0f;
					} else if (bits == 32) {
						int32_t *p = (int32_t *)data + (size_t)i * inCh;
						l = p[0] / 2147483648.0f;
						r = (inCh > 1 ? p[1] : p[0]) / 2147483648.0f;
					}
				}
				int16_t L = (int16_t)std::max(-32768.0f, std::min(32767.0f, l * 32767.0f));
				int16_t R = (int16_t)std::max(-32768.0f, std::min(32767.0f, r * 32767.0f));
				if (inRate == RATE) {
					tmp.push_back(L);
					tmp.push_back(R);
				} else {
					// lineare Interpolation auf 48 kHz
					double step = (double)inRate / RATE;
					while (resamplePos < 1.0) {
						tmp.push_back((int16_t)(lastL + (L - lastL) * resamplePos));
						tmp.push_back((int16_t)(lastR + (R - lastR) * resamplePos));
						resamplePos += step;
					}
					resamplePos -= 1.0;
				}
				lastL = L;
				lastR = R;
			}
			cc->ReleaseBuffer(frames);
			cap->push(tmp.data(), tmp.size());
		}
		if (FAILED(hr)) {
			logf("Aufnahme beendet (Prozess weg?): 0x%08lx", (unsigned long)hr);
			break;
		}
	}
	client->Stop();
	cc->Release();
	client->Release();
	if (mix) CoTaskMemFree(mix);
	CloseHandle(ev);
	cap->dead = true;
}

static std::unique_ptr<Capture> startCapture(IAudioClient *client, bool processLoopback, DWORD pid) {
	auto cap = std::make_unique<Capture>();
	cap->pid = pid;
	Capture *raw = cap.get();
	cap->thread = std::thread([raw, client, processLoopback] { captureLoop(raw, client, processLoopback); });
	return cap;
}

/** Klassisches Loopback des Standard-Ausgabegeräts (ganzer PC, auch Minecraft) – Notlösung für altes Windows. */
static IAudioClient *activateEndpointLoopback() {
	IMMDeviceEnumerator *en = nullptr;
	IMMDevice *dev = nullptr;
	IAudioClient *client = nullptr;
	if (SUCCEEDED(CoCreateInstance(CLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL, IID_IMMDeviceEnumerator, (void **)&en))
			&& SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev))) {
		dev->Activate(IID_IAudioClient, CLSCTX_ALL, nullptr, (void **)&client);
	}
	if (dev) dev->Release();
	if (en) en->Release();
	return client;
}

/** Prozesse, die gerade eine Ton-Sitzung am Standard-Ausgabegerät haben: PID -> Programmname. */
static std::map<DWORD, std::wstring> sessions() {
	std::map<DWORD, std::wstring> out;
	IMMDeviceEnumerator *en = nullptr;
	IMMDevice *dev = nullptr;
	IAudioSessionManager2 *mgr = nullptr;
	IAudioSessionEnumerator *se = nullptr;
	if (SUCCEEDED(CoCreateInstance(CLSID_MMDeviceEnumerator, nullptr, CLSCTX_ALL, IID_IMMDeviceEnumerator, (void **)&en))
			&& SUCCEEDED(en->GetDefaultAudioEndpoint(eRender, eConsole, &dev))
			&& SUCCEEDED(dev->Activate(IID_IAudioSessionManager2, CLSCTX_ALL, nullptr, (void **)&mgr))
			&& SUCCEEDED(mgr->GetSessionEnumerator(&se))) {
		int n = 0;
		se->GetCount(&n);
		for (int i = 0; i < n; i++) {
			IAudioSessionControl *c = nullptr;
			IAudioSessionControl2 *c2 = nullptr;
			if (FAILED(se->GetSession(i, &c))) continue;
			AudioSessionState state;
			if (SUCCEEDED(c->GetState(&state)) && state != AudioSessionStateExpired
					&& SUCCEEDED(c->QueryInterface(IID_IAudioSessionControl2, (void **)&c2))) {
				DWORD pid = 0;
				if (SUCCEEDED(c2->GetProcessId(&pid)) && pid != 0 && c2->IsSystemSoundsSession() != S_OK) {
					std::wstring name = exeName(pid);
					if (!name.empty()) out[pid] = name;
				}
				c2->Release();
			}
			c->Release();
		}
	}
	if (se) se->Release();
	if (mgr) mgr->Release();
	if (dev) dev->Release();
	if (en) en->Release();
	return out;
}

static std::set<std::wstring> splitNames(const char *arg) {
	std::set<std::wstring> s;
	std::string a = arg ? arg : "";
	size_t start = 0;
	while (start <= a.size()) {
		size_t comma = a.find(',', start);
		std::string part = a.substr(start, comma == std::string::npos ? std::string::npos : comma - start);
		if (!part.empty()) {
			int n = MultiByteToWideChar(CP_UTF8, 0, part.c_str(), -1, nullptr, 0);
			std::wstring w(n, 0);
			MultiByteToWideChar(CP_UTF8, 0, part.c_str(), -1, &w[0], n);
			w.resize(wcslen(w.c_str()));
			s.insert(lower(w));
		}
		if (comma == std::string::npos) break;
		start = comma + 1;
	}
	return s;
}

int main(int argc, char **argv) {
	if (argc < 3) {
		logf("Aufruf: mmaudio all|except|only|list <minecraftPid> [programme]");
		return 2;
	}
	std::string mode = argv[1];
	DWORD self = (DWORD)strtoul(argv[2], nullptr, 10);
	std::set<std::wstring> names = splitNames(argc > 3 ? argv[3] : "");
	CoInitializeEx(nullptr, COINIT_MULTITHREADED);

	if (mode == "list") {
		auto par = parents();
		std::set<DWORD> selfSet{self};
		std::set<std::wstring> seen;
		for (auto &[pid, name] : sessions()) {
			if (pid == self || pid == GetCurrentProcessId() || hasAncestor(pid, selfSet, par)) continue;
			if (seen.insert(lower(name)).second) printf("%s\n", utf8(name).c_str());
		}
		fflush(stdout);
		return 0;
	}

	_setmode(_fileno(stdout), _O_BINARY);
	SetPriorityClass(GetCurrentProcess(), ABOVE_NORMAL_PRIORITY_CLASS);
	std::map<DWORD, std::unique_ptr<Capture>> caps;
	bool dynamic = mode == "except" || mode == "only";
	if (!dynamic) {
		IAudioClient *c = activateProcessLoopback(self, false);
		if (c) {
			caps[0] = startCapture(c, true, 0);
			logf("Ton: alles außer Minecraft");
		} else if ((c = activateEndpointLoopback())) {
			caps[0] = startCapture(c, false, 0);
			logf("Ton: ganzer PC (altes Windows – Minecraft-Ton ist mit drin)");
		} else {
			logf("Keine Tonaufnahme möglich");
		}
	}

	timeBeginPeriod(1);
	LARGE_INTEGER freq, t0, now;
	QueryPerformanceFrequency(&freq);
	QueryPerformanceCounter(&t0);
	unsigned long long written = 0; // Frames
	std::vector<int32_t> acc;
	std::vector<int16_t> out;
	DWORD lastScan = 0;
	for (;;) {
		if (dynamic && GetTickCount() - lastScan > 2000) {
			lastScan = GetTickCount();
			auto par = parents();
			std::set<DWORD> selfSet{self, GetCurrentProcessId()};
			std::set<DWORD> wanted;
			for (auto &[pid, name] : sessions()) {
				if (selfSet.count(pid) || hasAncestor(pid, selfSet, par)) continue;
				bool listed = names.count(lower(name)) > 0;
				if (mode == "only" ? listed : !listed) wanted.insert(pid);
			}
			// Kinder von schon erfassten Prozessen nicht doppelt aufnehmen (Baum-Aufnahme enthält sie)
			std::set<DWORD> roots;
			for (DWORD pid : wanted) if (!hasAncestor(pid, wanted, par)) roots.insert(pid);
			for (auto it = caps.begin(); it != caps.end();) {
				if (!roots.count(it->first) || it->second->dead) it = caps.erase(it);
				else ++it;
			}
			for (DWORD pid : roots) {
				if (caps.count(pid)) continue;
				IAudioClient *c = activateProcessLoopback(pid, true);
				if (c) {
					caps[pid] = startCapture(c, true, pid);
					logf("Ton von: %s", utf8(exeName(pid)).c_str());
				}
			}
		}
		Sleep(10);
		QueryPerformanceCounter(&now);
		unsigned long long due = (unsigned long long)((now.QuadPart - t0.QuadPart) * (double)RATE / freq.QuadPart);
		if (due <= written) continue;
		size_t frames = (size_t)std::min<unsigned long long>(due - written, RATE / 2);
		written = due; // nach langem Hänger nicht nachholen
		acc.assign(frames * CH, 0);
		for (auto &[pid, cap] : caps) cap->mixInto(acc.data(), acc.size());
		out.resize(acc.size());
		for (size_t i = 0; i < acc.size(); i++) out[i] = (int16_t)std::max(-32768, std::min(32767, acc[i]));
		if (fwrite(out.data(), sizeof(int16_t), out.size(), stdout) != out.size() || fflush(stdout) != 0) {
			break; // ffmpeg ist weg -> Schluss
		}
	}
	caps.clear();
	timeEndPeriod(1);
	return 0;
}
