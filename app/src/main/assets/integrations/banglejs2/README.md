# Bangle.js 2 offline health snapshot protocol

This directory contains the watch-side half of a deliberately small, local-only protocol. It is
an original implementation based on official Espruino APIs; it does not contain or derive from
Gadgetbridge code.

## How the phone side works

The watch appears on the PWN.Health devices page when a search finds a device advertising the name
Espruino gives it, `Bangle.js` and the last four hex digits of its address (for example
`Bangle.js 7a3c`). The row says that the watch needs this app installed and open. Choosing Connect
then runs one exchange:

1. connect over BLE and discover the Nordic UART Service below;
2. enable notifications on TX (CCCD write);
3. write the request to RX;
4. feed every TX notification to the line decoder until one frame is complete;
5. disconnect.

The watch has five seconds after the request is written to answer, and one that sends nothing in
that time fails with "no answer" (the script ignores a second request within a second of its last
reply). Any other line that is not a valid frame fails the exchange at once — the Espruino console's
echo of the request, when the app is not running, among them. Blank lines and JSON objects with
fields of their own, which other watch apps write to the same UART, are set aside.

The snapshot is shown on the page — steps, heart rate when the watch sent one, battery — and is
saved only when the person taps Save. Steps are stored as the watch's total for the snapshot's local
day (the `ts` instant in the phone's time zone), one row per watch and day; a later sync of the same
day replaces it. The day's step figure is the larger of the phone's count, Health Connect and every
Bluetooth source, never their sum, and Bluetooth totals are not written to Health Connect as the
phone's steps. A heart rate is saved as a heart-rate measurement at `ts`. A snapshot whose day is
more than one day away from the phone's date is refused as an unset watch clock.

The watch is identified in the database by a salted fingerprint of its Bluetooth address, never by
the address itself.

## Manual bootstrap is required

`pwn_health_snapshot.js` must first be installed as a stored Bangle.js 2 application through
the official Espruino App Loader/Web IDE and then launched on the watch. PWN.Health intentionally does
not upload JavaScript through the Espruino REPL: doing that would reintroduce a generic remote-code
execution route that this protocol is designed to avoid.

While this app is running it detaches the JavaScript console from Bluetooth. Press the watch button
to return to the launcher; a watch restart also restores the normal boot environment. Do not use
"send to RAM and execute" for installation because the script detaches the console when it starts.

## BLE transport and v1 wire format

The script uses Espruino's built-in Nordic UART Service:

- service: `6e400001-b5a3-f393-e0a9-e50e24dcca9e`
- phone writes requests to RX: `6e400002-b5a3-f393-e0a9-e50e24dcca9e`
- watch sends notifications through TX: `6e400003-b5a3-f393-e0a9-e50e24dcca9e`

After subscribing to TX, write this exact ASCII request to RX:

```text
PWN_HEALTH 1\n
```

Here `\n` means one LF byte (`0x0A`), not the two printable characters backslash and `n`.

The response is one ASCII, newline-delimited JSON object. BLE may split it across notifications:

```json
{"v":1,"type":"health","ts":1785513600000,"steps":12345,"hr":72,"battery":87}
```

`steps` is the watch's day count. `hr` is the best reading in the current ten-minute health block
only when Bangle.js reports at least 80% confidence; otherwise it is `null`. `battery` is an estimate
from 0 to 100, and `ts` is the watch RTC in Unix milliseconds. The watch clock must be set correctly;
the phone parser rejects timestamps outside 2000-01-01 through 2100-01-01.

The phone parser accepts JSON whitespace and any field order, but requires exactly the six fields
above, integer numeric values, protocol version 1, and a maximum frame length of 192 bytes.

## Security boundary and limitation

The default Espruino UART is normally a JavaScript REPL. This script calls
`E.setConsole(null,{force:true})`, never calls `eval`/`Function`, buffers at most 32 request bytes,
recognizes only the fixed request above, and rate-limits responses to one per second. Malformed and
unknown input receives no response.

This prevents inbound UART text from being executed while the app is active. It does **not** add
BLE authentication: Espruino documents that its UART is open unless pairing/encryption is separately
configured. Therefore a nearby BLE client may request the snapshot while this app is running.

The phone side does not authenticate the watch either. It neither bonds nor encrypts the link, and
any device that advertises a matching name and answers with a valid frame is taken for the watch.
That is why the snapshot waits on the page for Save instead of being written on arrival.

## Official sources

- [Espruino Bluetooth LE UART (NUS)](https://www.espruino.com/BLE%20UART)
- [Nordic Semiconductor NUS UUIDs and characteristic directions](https://docs.nordicsemi.com/r/bundle/nrf5_sdk_v15.2.0/page/ble_sdk_app_nus_eval.html)
- [Espruino BLE security and disabling the console](https://www.espruino.com/BLE%20Security)
- [Bangle.js API: health status, battery, Bluetooth and console](https://www.espruino.com/ReferenceBANGLEJS)
- [Bangle.js 2 official hardware/software page](https://www.espruino.com/Bangle.js2)
