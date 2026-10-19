using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Threading;
using static DragWheel.NativeMethods;

namespace DragWheel
{
    /// <summary>
    /// Watches the mouse in every app with a low-level hook.
    /// Fires ShiftDragStarted when the left button is held, the mouse has moved a bit,
    /// and Shift is down. It does not know yet whether files are being dragged;
    /// the wheel finds that out when the drag enters it.
    ///
    /// The hook runs on its own thread with its own message loop. Windows silently removes
    /// low-level hooks that answer slowly, so it must never wait for the UI thread.
    /// </summary>
    internal sealed class MouseHook : IDisposable
    {
        private const int DragThreshold = 12; // pixels

        public event Action<int, int>? ShiftDragStarted;
        public event Action? LeftButtonReleased;

        private readonly Dispatcher _dispatcher;
        private readonly LowLevelMouseProc _proc; // keep a reference so the GC does not collect it
        private readonly uint _ownProcessId = (uint)Environment.ProcessId;
        private IntPtr _hook = IntPtr.Zero;
        private Thread? _thread;
        private uint _threadId;

        // Only touched on the hook thread.
        private bool _down;
        private bool _fired;
        private bool _ignore;
        private int _startX;
        private int _startY;

        public MouseHook(Dispatcher dispatcher)
        {
            _dispatcher = dispatcher;
            _proc = Callback;
        }

        /// <summary>Starts the hook thread. Throws if Windows refuses the hook.</summary>
        public void Start()
        {
            Exception? error = null;
            using var started = new ManualResetEventSlim(false);

            _thread = new Thread(() =>
            {
                _threadId = GetCurrentThreadId();
                using (var module = Process.GetCurrentProcess().MainModule)
                    _hook = SetWindowsHookEx(WH_MOUSE_LL, _proc, GetModuleHandle(module?.ModuleName), 0);
                if (_hook == IntPtr.Zero)
                    error = new Win32Exception(Marshal.GetLastWin32Error());
                started.Set();
                if (error != null) return;

                // Message loop: the hook callback is delivered through it.
                while (GetMessage(out var msg, IntPtr.Zero, 0, 0) > 0)
                {
                    TranslateMessage(ref msg);
                    DispatchMessage(ref msg);
                }
                UnhookWindowsHookEx(_hook);
                _hook = IntPtr.Zero;
            })
            {
                IsBackground = true,
                Name = "DragWheel mouse hook",
                Priority = ThreadPriority.AboveNormal,
            };
            _thread.Start();
            started.Wait();
            if (error != null) throw error;
        }

        private IntPtr Callback(int nCode, IntPtr wParam, IntPtr lParam)
        {
            // Keep this fast. Windows drops slow hooks.
            if (nCode >= 0)
            {
                var info = Marshal.PtrToStructure<MSLLHOOKSTRUCT>(lParam);
                switch ((int)wParam)
                {
                    case WM_LBUTTONDOWN:
                        _down = true;
                        _fired = false;
                        _startX = info.pt.X;
                        _startY = info.pt.Y;
                        _ignore = IsOwnWindow(info.pt);
                        break;

                    case WM_MOUSEMOVE:
                        if (_down && !_fired && !_ignore)
                        {
                            int dx = info.pt.X - _startX;
                            int dy = info.pt.Y - _startY;
                            if (dx * dx + dy * dy > DragThreshold * DragThreshold &&
                                (GetAsyncKeyState(VK_SHIFT) & 0x8000) != 0)
                            {
                                _fired = true;
                                int x = info.pt.X, y = info.pt.Y;
                                _dispatcher.InvokeAsync(() => ShiftDragStarted?.Invoke(x, y));
                            }
                        }
                        break;

                    case WM_LBUTTONUP:
                        _down = false;
                        _dispatcher.InvokeAsync(() => LeftButtonReleased?.Invoke());
                        break;
                }
            }
            return CallNextHookEx(_hook, nCode, wParam, lParam);
        }

        private bool IsOwnWindow(POINT pt)
        {
            var hwnd = WindowFromPoint(pt);
            if (hwnd == IntPtr.Zero) return false;
            GetWindowThreadProcessId(hwnd, out uint pid);
            return pid == _ownProcessId;
        }

        public void Dispose()
        {
            if (_thread != null && _threadId != 0)
            {
                PostThreadMessage(_threadId, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
                _thread.Join(1000);
                _thread = null;
            }
        }
    }
}
