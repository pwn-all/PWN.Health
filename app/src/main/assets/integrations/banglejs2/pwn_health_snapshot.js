/*
 * PWN.Health Bangle.js 2 offline health snapshot bridge, protocol v1.
 * Original implementation based only on the official Espruino APIs documented at:
 * https://www.espruino.com/BLE%20UART
 * https://www.espruino.com/BLE%20Security
 * https://www.espruino.com/ReferenceBANGLEJS#l_Bangle_getHealthStatus
 */
(function () {
  "use strict";

  var REQUEST = "PWN_HEALTH 1";
  var MAX_COMMAND_BYTES = 32;
  var MIN_REPLY_INTERVAL_MS = 1000;
  var commandBuffer = "";
  var droppingCommand = false;
  var lastReplyAt = 0;
  var stopped = false;

  function boundedInteger(value, minimum, maximum, fallback) {
    if (typeof value !== "number" || !isFinite(value)) return fallback;
    value = Math.floor(value);
    if (value < minimum) return minimum;
    if (value > maximum) return maximum;
    return value;
  }

  function recentHeartRate(status) {
    var bpm = status && status.bpm;
    var confidence = status && status.bpmConfidence;
    if (typeof bpm !== "number" || !isFinite(bpm) || bpm < 20 || bpm > 250) return null;
    if (typeof confidence !== "number" || !isFinite(confidence) || confidence < 80) return null;
    return Math.round(bpm);
  }

  function writeSnapshot() {
    var now = Date.now();
    if (now >= lastReplyAt && now - lastReplyAt < MIN_REPLY_INTERVAL_MS) return;
    lastReplyAt = now;

    var day = Bangle.getHealthStatus("day") || {};
    var recent = Bangle.getHealthStatus("10min") || {};
    var snapshot = {
      v: 1,
      type: "health",
      ts: Math.round(now),
      steps: boundedInteger(day.steps, 0, 1000000, 0),
      hr: recentHeartRate(recent),
      battery: boundedInteger(E.getBattery(), 0, 100, 0)
    };
    Bluetooth.write(JSON.stringify(snapshot) + "\n");
  }

  function finishCommand() {
    var command = commandBuffer;
    if (command.charAt(command.length - 1) === "\r") {
      command = command.slice(0, -1);
    }
    if (!droppingCommand && command === REQUEST) writeSnapshot();
    commandBuffer = "";
    droppingCommand = false;
  }

  function onBluetoothData(data) {
    var index;
    var code;
    for (index = 0; index < data.length; index += 1) {
      code = data.charCodeAt(index);
      if (code === 10) {
        finishCommand();
      } else if (!droppingCommand) {
        if ((code < 32 && code !== 13) || code > 126 || commandBuffer.length >= MAX_COMMAND_BYTES) {
          commandBuffer = "";
          droppingCommand = true;
        } else {
          commandBuffer += data.charAt(index);
        }
      }
    }
  }

  function restoreConsole() {
    if (stopped) return;
    stopped = true;
    Bluetooth.removeListener("data", onBluetoothData);
    Bluetooth.setConsole(true);
  }

  E.on("kill", restoreConsole);
  E.setConsole(null, {force: true});
  Bluetooth.on("data", onBluetoothData);

  E.showMessage("PWN.Health BLE\n\nPress button\nto exit");
  Bangle.setUI({
    mode: "custom",
    btn: Bangle.showLauncher
  });
}());
