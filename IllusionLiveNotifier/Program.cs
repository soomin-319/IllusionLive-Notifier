namespace IllusionLiveNotifier;

internal static class Program
{
    [STAThread]
    private static async Task Main(string[] args)
    {
        if (args.Contains("--self-test", StringComparer.OrdinalIgnoreCase))
        {
            Environment.ExitCode = SelfTest.Run();
            return;
        }

        if (args.Contains("--check-live", StringComparer.OrdinalIgnoreCase))
        {
            Environment.ExitCode = await SelfTest.CheckLiveAsync();
            return;
        }

        using var singleInstance = new Mutex(true, "Local\\IllusionLiveNotifier.SingleInstance", out var created);
        if (!created)
        {
            MessageBox.Show("일루전 라이브 알리미가 이미 실행 중입니다.\n작업 표시줄 알림 영역을 확인해 주세요.",
                "일루전 라이브 알리미", MessageBoxButtons.OK, MessageBoxIcon.Information);
            return;
        }

        ApplicationConfiguration.Initialize();
        Application.Run(new MainForm(args.Contains("--background", StringComparer.OrdinalIgnoreCase)));
    }
}
