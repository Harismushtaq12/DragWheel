using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using System.Windows.Media;

namespace DragWheel
{
    /// <summary>A small window that asks one question, e.g. a password or watermark text.</summary>
    internal static class InputDialog
    {
        public static string? Ask(string title, string question, string initial, bool password)
        {
            var ink = new SolidColorBrush(Color.FromRgb(0x14, 0x1C, 0x25));
            var ring = new SolidColorBrush(Color.FromRgb(0x1D, 0x27, 0x33));
            var text = new SolidColorBrush(Color.FromRgb(0xEE, 0xF2, 0xF5));
            var mint = new SolidColorBrush(Color.FromRgb(0x7F, 0xE0, 0xC2));

            var window = new Window
            {
                Title = title,
                Width = 420,
                SizeToContent = SizeToContent.Height,
                WindowStartupLocation = WindowStartupLocation.CenterScreen,
                ResizeMode = ResizeMode.NoResize,
                Background = ink,
                Topmost = true,
                ShowInTaskbar = true,
            };

            var panel = new StackPanel { Margin = new Thickness(20) };
            panel.Children.Add(new TextBlock { Text = question, Foreground = text, FontSize = 14, Margin = new Thickness(0, 0, 0, 10), TextWrapping = TextWrapping.Wrap });

            Control box;
            if (password)
            {
                box = new PasswordBox { Password = initial };
            }
            else
            {
                box = new TextBox { Text = initial };
                ((TextBox)box).SelectAll();
            }
            box.FontSize = 15;
            box.Padding = new Thickness(6);
            box.Background = ring;
            box.Foreground = text;
            box.BorderBrush = mint;
            if (box is TextBox tb) tb.CaretBrush = mint;
            panel.Children.Add(box);

            var buttons = new StackPanel { Orientation = Orientation.Horizontal, HorizontalAlignment = HorizontalAlignment.Right, Margin = new Thickness(0, 16, 0, 0) };
            var cancel = new Button { Content = "Cancel", Width = 90, Height = 32, Margin = new Thickness(0, 0, 8, 0), IsCancel = true };
            var ok = new Button { Content = "OK", Width = 90, Height = 32, IsDefault = true, Background = mint, BorderBrush = mint };
            buttons.Children.Add(cancel);
            buttons.Children.Add(ok);
            panel.Children.Add(buttons);
            window.Content = panel;

            string? result = null;
            ok.Click += (_, _) =>
            {
                result = box is PasswordBox pb ? pb.Password : ((TextBox)box).Text;
                window.DialogResult = true;
            };
            window.Loaded += (_, _) => { box.Focus(); Keyboard.Focus(box); window.Activate(); };
            return window.ShowDialog() == true ? result : null;
        }
    }
}
