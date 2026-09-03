import("./main.mjs").catch(error => {
  console.error("Navis startup failed", error);
  dump(`Navis startup failed: ${error}\n${error.stack || ""}\n`);
  document.documentElement.dataset.l10nReady = "fallback";
  document.title = "Navis startup failed";
  const startupStatus = document.getElementById("loading-state");
  if (startupStatus) {
    startupStatus.textContent = `Startup failed: ${error.message}`;
  }
});
