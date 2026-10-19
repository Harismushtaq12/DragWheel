using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Interop;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using System.Windows.Threading;
using static DragWheel.NativeMethods;

namespace DragWheel
{
    /// <summary>
    /// The radial menu. It looks like a dial: a dark ring of actions around a light knob.
    /// The ring is one drop target; the action under the cursor is found from the angle.
    /// Hovering the knob for a moment switches between Convert and Tools.
    /// </summary>
    public partial class WheelWindow : Window
    {
        private const double Center = 190;
        private const double OuterR = 178;
        private const double InnerR = 68;
        private const double KnobR = 58;
        private const double GapDeg = 1.6;

        // Palette: slate-ink ring, light knob, mint for the live choice.
        private static readonly Brush RingConvert = Freeze(Color.FromArgb(0xF2, 0x1D, 0x27, 0x33));
        private static readonly Brush RingTools = Freeze(Color.FromArgb(0xF2, 0x2A, 0x23, 0x38));
        private static readonly Brush SegIdleConvert = Freeze(Color.FromRgb(0x2C, 0x3A, 0x49));
        private static readonly Brush SegIdleTools = Freeze(Color.FromRgb(0x3A, 0x31, 0x4B));
        private static readonly Brush SegHot = Freeze(Color.FromRgb(0x7F, 0xE0, 0xC2));
        private static readonly Brush TextLight = Freeze(Color.FromRgb(0xEE, 0xF2, 0xF5));
        private static readonly Brush TextMuted = Freeze(Color.FromRgb(0x9A, 0xA8, 0xB6));
        private static readonly Brush TextDark = Freeze(Color.FromRgb(0x10, 0x1B, 0x17));
        private static readonly Brush Knob = Freeze(Color.FromRgb(0xEE, 0xF2, 0xF5));
        private static readonly Brush KnobText = Freeze(Color.FromRgb(0x1D, 0x27, 0x33));
        private static readonly Brush KnobMuted = Freeze(Color.FromRgb(0x5D, 0x6B, 0x7A));
        private static readonly FontFamily UiFont = new FontFamily("Segoe UI Variable Text, Segoe UI");

        public event Action<WheelAction, string[]>? ActionPicked;

        private readonly DispatcherTimer _armTimer;   // hide if no files arrive
        private readonly DispatcherTimer _knobTimer;  // switch Convert/Tools
        private readonly DispatcherTimer _hideTimer;  // hide after mouse release
        private readonly DispatcherTimer _watchdog;   // hide if the release was missed

        private string[] _files = Array.Empty<string>();
        private List<WheelAction> _actions = new();
        private bool _gotFiles;
        private bool _toolsMode;
        private bool _knobArmed = true;
        private bool _shown;
        private int _active = -1;
        private int _targetX;
        private int _targetY;

        /// <summary>
        /// Click mode: opened from the right-click menu, Send to, or the app window.
        /// The wheel takes focus and works with mouse clicks and the keyboard.
        /// </summary>
        private bool _clickMode;
        private bool _pressed; // the press started on the wheel (not the menu click that opened it)

        public bool IsClickOpen => _clickMode && _shown;
        public string[] Files => _files;

        private readonly List<Path> _segments = new();
        private readonly List<TextBlock> _titles = new();
        private readonly List<TextBlock> _hints = new();
        private TextBlock? _knobTop;
        private TextBlock? _knobMain;
        private TextBlock? _knobBottom;

        public WheelWindow()
        {
            InitializeComponent();

            _armTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(900) };
            _armTimer.Tick += (_, _) => { _armTimer.Stop(); if (!_gotFiles) HideWheel(); };

            _knobTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(450) };
            _knobTimer.Tick += (_, _) => { _knobTimer.Stop(); ToggleMode(); };

            _hideTimer = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(700) };
            _hideTimer.Tick += (_, _) => { _hideTimer.Stop(); HideWheel(); };

            // Safety net: if we miss the button-up message (UAC prompt, Ctrl+Alt+Del...),
            // notice the released button ourselves so the wheel never stays stuck on screen.
            _watchdog = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(250) };
            _watchdog.Tick += (_, _) =>
            {
                if ((GetAsyncKeyState(VK_LBUTTON) & 0x8000) == 0)
                {
                    _watchdog.Stop();
                    if (!_hideTimer.IsEnabled) _hideTimer.Start();
                }
            };

            SourceInitialized += (_, _) => SetNoActivate(true);
            new WindowInteropHelper(this).EnsureHandle(); // so the style is set before the first Show

            MouseMove += OnMouseMoveClick;
            MouseLeftButtonDown += (_, _) => _pressed = true;
            MouseLeftButtonUp += OnMouseUpClick;
            PreviewKeyDown += OnKey;
            Deactivated += (_, _) => { if (_clickMode) HideWheel(); };
            DpiChanged += (_, _) => Dispatcher.InvokeAsync(PlaceAtTarget);

            DragEnter += OnDragEnter;
            DragOver += OnDragOver;
            DragLeave += OnDragLeave;
            Drop += OnDrop;
        }

        /// <summary>Called when a Shift-drag starts. Shows a nearly invisible wheel under the cursor.</summary>
        public void Arm(int x, int y)
        {
            if (_clickMode) HideWheel();
            SetNoActivate(true);
            _targetX = x;
            _targetY = y;
            _files = Array.Empty<string>();
            _actions = new List<WheelAction>();
            _gotFiles = false;
            _toolsMode = false;
            // The wheel opens with the cursor on the knob. Only switch modes after the
            // cursor has left the knob once and come back, or a short pause would flip it.
            _knobArmed = false;
            _active = -1;
            _hideTimer.Stop();
            _knobTimer.Stop();

            Build();

            // Almost invisible until we know files are being dragged.
            // A tiny alpha keeps the pixels hit-testable, so the drag can still "enter".
            Root.BeginAnimation(OpacityProperty, null);
            Root.Opacity = 0.02;

            if (!_shown)
            {
                Show();
                _shown = true;
            }
            PlaceAtTarget();

            _armTimer.Stop();
            _armTimer.Start();
        }

        public void MouseReleased()
        {
            if (!_shown || _clickMode) return;
            // The drop arrives slightly after the button is released.
            _hideTimer.Stop();
            _hideTimer.Start();
        }

        /// <summary>Shows the wheel for files chosen another way (right-click menu, Send to, app window).</summary>
        public void ShowFor(string[] files, int x, int y)
        {
            if (files.Length == 0) return;
            HideWheel();
            _clickMode = true;
            _targetX = x;
            _targetY = y;
            _files = files;
            _gotFiles = true;
            _toolsMode = false;
            _knobArmed = true;
            _active = -1;
            _actions = Catalog.For(_files, _toolsMode);
            Build();
            Root.BeginAnimation(OpacityProperty, null);
            Root.Opacity = 1;
            SetNoActivate(false);
            _pressed = false;
            Show();
            SetNoActivate(false);
            _shown = true;
            PlaceAtTarget();
            var hwnd = new WindowInteropHelper(this).Handle;
            SetForegroundWindow(hwnd);
            Activate();
            Focus();
        }

        /// <summary>Called on any left click on the screen: a click outside the wheel closes it.</summary>
        public void ScreenClicked(int x, int y)
        {
            if (!_clickMode || !_shown) return;
            var hwnd = new WindowInteropHelper(this).Handle;
            GetWindowRect(hwnd, out var r);
            double cx = (r.Left + r.Right) / 2.0, cy = (r.Top + r.Bottom) / 2.0;
            double radius = (r.Right - r.Left) / 2.0 * (OuterR / Center);
            if (Math.Pow(x - cx, 2) + Math.Pow(y - cy, 2) > radius * radius) HideWheel();
        }

        private void SetNoActivate(bool on)
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;
            int style = GetWindowLong(hwnd, GWL_EXSTYLE) | WS_EX_TOOLWINDOW;
            style = on ? style | WS_EX_NOACTIVATE : style & ~WS_EX_NOACTIVATE;
            SetWindowLong(hwnd, GWL_EXSTYLE, style);
        }

        // ---------- Click and keyboard ----------

        private void OnMouseMoveClick(object sender, System.Windows.Input.MouseEventArgs e)
        {
            if (!_clickMode) return;
            var p = e.GetPosition(Root);
            double dx = p.X - Center, dy = p.Y - Center;
            double d = Math.Sqrt(dx * dx + dy * dy);
            SetActive(d >= InnerR && d <= OuterR + 10 && _actions.Count > 0 ? IndexAt(dx, dy) : -1);
        }

        private void OnMouseUpClick(object sender, System.Windows.Input.MouseButtonEventArgs e)
        {
            if (!_clickMode || !_pressed) return;
            _pressed = false;
            var p = e.GetPosition(Root);
            double dx = p.X - Center, dy = p.Y - Center;
            double d = Math.Sqrt(dx * dx + dy * dy);
            if (d < InnerR) ToggleMode();
            else if (d <= OuterR + 10 && _actions.Count > 0) Pick(IndexAt(dx, dy));
            else HideWheel();
        }

        private void OnKey(object sender, System.Windows.Input.KeyEventArgs e)
        {
            if (!_clickMode) return;
            int n = _actions.Count;
            switch (e.Key)
            {
                case System.Windows.Input.Key.Escape: HideWheel(); break;
                case System.Windows.Input.Key.Tab: ToggleMode(); break;
                case System.Windows.Input.Key.Right:
                case System.Windows.Input.Key.Down:
                    if (n > 0) SetActive(_active < 0 ? 0 : (_active + 1) % n);
                    break;
                case System.Windows.Input.Key.Left:
                case System.Windows.Input.Key.Up:
                    if (n > 0) SetActive(_active <= 0 ? n - 1 : _active - 1);
                    break;
                case System.Windows.Input.Key.Enter:
                case System.Windows.Input.Key.Space:
                    if (_active >= 0) Pick(_active);
                    break;
                default: return;
            }
            e.Handled = true;
        }

        private void Pick(int index)
        {
            if (index < 0 || index >= _actions.Count) return;
            var action = _actions[index];
            var files = _files;
            HideWheel();
            Dispatcher.InvokeAsync(() => ActionPicked?.Invoke(action, files));
        }

        private void HideWheel()
        {
            _clickMode = false;
            _armTimer.Stop();
            _knobTimer.Stop();
            _hideTimer.Stop();
            _watchdog.Stop();
            if (!_shown) return;
            Hide();
            _shown = false;
        }

        // ---------- Drag handling ----------

        private void OnDragEnter(object sender, DragEventArgs e)
        {
            if (!e.Data.GetDataPresent(DataFormats.FileDrop))
            {
                e.Effects = DragDropEffects.None;
                e.Handled = true;
                return;
            }

            if (!_gotFiles)
            {
                _files = (e.Data.GetData(DataFormats.FileDrop) as string[]) ?? Array.Empty<string>();
                if (_files.Length == 0) { e.Effects = DragDropEffects.None; e.Handled = true; return; }

                _gotFiles = true;
                _armTimer.Stop();
                _watchdog.Start();
                _actions = Catalog.For(_files, _toolsMode);
                Build();
                Root.BeginAnimation(OpacityProperty,
                    new DoubleAnimation(1, TimeSpan.FromMilliseconds(120)) { EasingFunction = new QuadraticEase() });
            }
            _hideTimer.Stop();
            OnDragOver(sender, e);
        }

        private void OnDragOver(object sender, DragEventArgs e)
        {
            e.Handled = true;
            e.Effects = DragDropEffects.None;
            if (!_gotFiles) return;

            var p = e.GetPosition(Root);
            double dx = p.X - Center, dy = p.Y - Center;
            double d = Math.Sqrt(dx * dx + dy * dy);

            if (d < InnerR)
            {
                SetActive(-1);
                if (_knobArmed && !_knobTimer.IsEnabled) _knobTimer.Start();
                return;
            }

            _knobTimer.Stop();
            _knobArmed = true;

            if (d <= OuterR + 10 && _actions.Count > 0)
            {
                SetActive(IndexAt(dx, dy));
                // Always Copy. Explorer treats Shift-drag as "move", and a Move result would delete the original.
                e.Effects = DragDropEffects.Copy;
            }
            else
            {
                SetActive(-1);
            }
        }

        private void OnDragLeave(object sender, DragEventArgs e)
        {
            _knobTimer.Stop();
            SetActive(-1);
            e.Handled = true;
        }

        private void OnDrop(object sender, DragEventArgs e)
        {
            e.Handled = true;
            if (!_gotFiles || _active < 0 || _active >= _actions.Count)
            {
                e.Effects = DragDropEffects.None;
                HideWheel();
                return;
            }

            var action = _actions[_active];
            var files = _files;
            e.Effects = DragDropEffects.Copy;
            HideWheel();
            // Let the drop return to Explorer first, then start the work.
            Dispatcher.InvokeAsync(() => ActionPicked?.Invoke(action, files));
        }

        private void ToggleMode()
        {
            if (!_gotFiles) return;
            _toolsMode = !_toolsMode;
            _knobArmed = _clickMode; // dragging: must leave the knob before it can switch again
            _actions = Catalog.For(_files, _toolsMode);
            _active = -1;
            Build();
        }

        private int IndexAt(double dx, double dy)
        {
            int n = _actions.Count;
            double span = 360.0 / n;
            double angle = Math.Atan2(dy, dx) * 180 / Math.PI;      // 0 = right, clockwise on screen
            double fromTop = angle + 90 + span / 2;                 // first segment is centred at the top
            fromTop = ((fromTop % 360) + 360) % 360;
            return Math.Min(n - 1, (int)(fromTop / span));
        }

        // ---------- Drawing ----------

        private void Build()
        {
            Root.Children.Clear();
            _segments.Clear();
            _titles.Clear();
            _hints.Clear();

            var ring = new Ellipse
            {
                Width = OuterR * 2,
                Height = OuterR * 2,
                Fill = _toolsMode ? RingTools : RingConvert,
                IsHitTestVisible = false
            };
            Canvas.SetLeft(ring, Center - OuterR);
            Canvas.SetTop(ring, Center - OuterR);
            Root.Children.Add(ring);

            int n = _actions.Count;
            if (n > 0)
            {
                bool ffmpegMissing = _actions.Any(a => a.Engine == Engine.Ffmpeg) && Ffmpeg.Find() == null;
                bool officeMissing = _actions.Any(a => a.Engine == Engine.Office) && _files.Length > 0 &&
                    Tools.OfficeOps.FamilyOf(_files[0]) is { } fam && !Tools.OfficeOps.CanConvert(fam);
                double span = 360.0 / n;
                double labelR = (OuterR + InnerR) / 2 + 4;
                // Labels must fit their slice: smaller when there are many slices.
                double labelW = Math.Min(92, 2 * Math.PI * labelR / n * 0.95);
                for (int i = 0; i < n; i++)
                {
                    double mid = -90 + i * span;
                    double a0 = mid - span / 2 + GapDeg / 2;
                    double a1 = mid + span / 2 - GapDeg / 2;
                    if (n == 1) { a0 = -89.9; a1 = 269.9; }

                    var seg = new Path
                    {
                        Data = Wedge(a0, a1, InnerR + 4, OuterR - 4),
                        Fill = _toolsMode ? SegIdleTools : SegIdleConvert,
                        IsHitTestVisible = false
                    };
                    Root.Children.Add(seg);
                    _segments.Add(seg);

                    var action = _actions[i];
                    var title = new TextBlock
                    {
                        Text = action.Title,
                        FontFamily = UiFont,
                        FontSize = n > 9 ? 12 : n > 8 ? 13 : action.Title.Length <= 5 ? 17 : 13.5,
                        FontWeight = FontWeights.SemiBold,
                        Foreground = TextLight,
                        TextAlignment = TextAlignment.Center,
                        Width = labelW,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        IsHitTestVisible = false
                    };
                    var hint = new TextBlock
                    {
                        Text = action.Engine == Engine.Ffmpeg && ffmpegMissing ? "needs ffmpeg"
                            : action.Engine == Engine.Office && officeMissing ? "needs Office"
                            : n > 10 ? "" : action.Hint,
                        FontFamily = UiFont,
                        FontSize = n > 8 ? 9.5 : 10.5,
                        Foreground = TextMuted,
                        TextAlignment = TextAlignment.Center,
                        Width = labelW,
                        TextTrimming = TextTrimming.CharacterEllipsis,
                        IsHitTestVisible = false
                    };
                    var stack = new StackPanel { IsHitTestVisible = false };
                    stack.Children.Add(title);
                    stack.Children.Add(hint);

                    double rad = mid * Math.PI / 180;
                    double x = Center + labelR * Math.Cos(rad);
                    double y = Center + labelR * Math.Sin(rad);
                    if (n == 1) { x = Center; y = Center - labelR; }
                    Canvas.SetLeft(stack, x - labelW / 2);
                    Canvas.SetTop(stack, y - 19);
                    Root.Children.Add(stack);
                    _titles.Add(title);
                    _hints.Add(hint);
                }
            }

            // The knob.
            var knob = new Ellipse
            {
                Width = KnobR * 2,
                Height = KnobR * 2,
                Fill = Knob,
                IsHitTestVisible = false
            };
            Canvas.SetLeft(knob, Center - KnobR);
            Canvas.SetTop(knob, Center - KnobR);
            Root.Children.Add(knob);

            var knobStack = new StackPanel { Width = KnobR * 2 - 12, IsHitTestVisible = false };
            _knobTop = KnobLine(SourceLabel(), 11, KnobMuted, FontWeights.Normal);
            _knobMain = KnobLine(ModeLabel(), 16, KnobText, FontWeights.Bold);
            _knobBottom = KnobLine(!_gotFiles ? "" : _clickMode ? (_toolsMode ? "click: convert" : "click: tools")
                : (_toolsMode ? "hold here to convert" : "hold here for tools"),
                9.5, KnobMuted, FontWeights.Normal);
            knobStack.Children.Add(_knobTop);
            knobStack.Children.Add(_knobMain);
            knobStack.Children.Add(_knobBottom);
            Canvas.SetLeft(knobStack, Center - KnobR + 6);
            Canvas.SetTop(knobStack, Center - 27);
            Root.Children.Add(knobStack);

            SetActive(_active, force: true);
        }

        private static TextBlock KnobLine(string text, double size, Brush brush, FontWeight weight) => new()
        {
            Text = text,
            FontFamily = UiFont,
            FontSize = size,
            FontWeight = weight,
            Foreground = brush,
            TextAlignment = TextAlignment.Center,
            TextTrimming = TextTrimming.CharacterEllipsis,
            IsHitTestVisible = false
        };

        private string SourceLabel()
        {
            if (_files.Length == 0) return "";
            if (_files.Length > 1) return $"{_files.Length} files";
            var f = _files[0];
            if (System.IO.Directory.Exists(f)) return "Folder";
            var ext = System.IO.Path.GetExtension(f).TrimStart('.').ToUpperInvariant();
            return ext.Length > 0 ? ext : "File";
        }

        private string ModeLabel() => !_gotFiles ? "Drop here" : _toolsMode ? "Tools" : "Convert";

        private void SetActive(int index, bool force = false)
        {
            if (index == _active && !force) return;
            _active = index;
            for (int i = 0; i < _segments.Count; i++)
            {
                bool hot = i == index;
                _segments[i].Fill = hot ? SegHot : (_toolsMode ? SegIdleTools : SegIdleConvert);
                _titles[i].Foreground = hot ? TextDark : TextLight;
                _hints[i].Foreground = hot ? TextDark : TextMuted;
            }
            if (_knobMain != null)
                _knobMain.Text = index >= 0 && index < _actions.Count ? _actions[index].Title : ModeLabel();
        }

        private static Geometry Wedge(double a0, double a1, double r0, double r1)
        {
            static Point P(double angleDeg, double r)
            {
                double a = angleDeg * Math.PI / 180;
                return new Point(Center + r * Math.Cos(a), Center + r * Math.Sin(a));
            }

            bool large = a1 - a0 > 180;
            var figure = new PathFigure { StartPoint = P(a0, r1), IsClosed = true };
            figure.Segments.Add(new ArcSegment(P(a1, r1), new Size(r1, r1), 0, large, SweepDirection.Clockwise, true));
            figure.Segments.Add(new LineSegment(P(a1, r0), true));
            figure.Segments.Add(new ArcSegment(P(a0, r0), new Size(r0, r0), 0, large, SweepDirection.Counterclockwise, true));
            var geometry = new PathGeometry(new[] { figure });
            geometry.Freeze();
            return geometry;
        }

        private static Brush Freeze(Color c)
        {
            var b = new SolidColorBrush(c);
            b.Freeze();
            return b;
        }

        // ---------- Placement ----------

        /// <summary>Centres the window on the cursor, in physical pixels, kept inside the screen's work area.</summary>
        private void PlaceAtTarget()
        {
            var hwnd = new WindowInteropHelper(this).Handle;
            if (hwnd == IntPtr.Zero) return;

            GetWindowRect(hwnd, out var r);
            int w = r.Right - r.Left, h = r.Bottom - r.Top;

            var pt = new POINT { X = _targetX, Y = _targetY };
            var info = new MONITORINFO { cbSize = Marshal.SizeOf<MONITORINFO>() };
            GetMonitorInfo(MonitorFromPoint(pt, MONITOR_DEFAULTTONEAREST), ref info);
            var work = info.rcWork;

            int x = Math.Max(work.Left, Math.Min(_targetX - w / 2, work.Right - w));
            int y = Math.Max(work.Top, Math.Min(_targetY - h / 2, work.Bottom - h));
            SetWindowPos(hwnd, HWND_TOPMOST, x, y, 0, 0, SWP_NOSIZE | (_clickMode ? 0u : SWP_NOACTIVATE));
        }
    }
}
