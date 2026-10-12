# KMR: Windows EC2 trial for MT5

Use this route to test KMR signals while your own PC is switched off. Keep KMR's
React/Java app on Render and PostgreSQL on Neon. The EC2 Windows machine runs
your broker's MT5 desktop terminal, the Python data bridge, and optionally the
demo execution worker. All three must run on the remote Windows machine for
signals and demo orders to continue while your own PC is off. The supplied
executor rejects live accounts; see DEMO_EXECUTION.md for its execution policy.

## Check account eligibility first

Use your own personal AWS account. In Billing, confirm the account plan,
remaining credits, and expiry date before launching anything. For eligible new
accounts, the Free account plan ends at six months or credit exhaustion,
whichever occurs first. It is not six months of unlimited Windows compute.
The Free plan closes at expiry unless upgraded; Paid plans can incur charges
beyond credits. Keep the Free plan for this zero-payment trial.

If you already have an account, do not assume that new-account credit offers
apply to it. Verify its actual plan and credits in the AWS console. This guide
does not create accounts or upgrade plans.

AWS references:
- https://docs.aws.amazon.com/awsaccountbilling/latest/aboutv2/free-tier-plans.html
- https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/ec2-free-tier-usage.html

## Check the app and database quotas too

This is a temporary cloud demo trial, not an unlimited free 24/7 service.
Render Free web services have 750 instance hours per workspace per month, idle
spin-down after 15 minutes, bandwidth limits, and possible restarts. The active
bridge and executor send real requests, but this does not guarantee availability.

Neon Free currently includes 100 CU-hours per project per month. At 0.25 CU,
this covers about 400 active hours. For comparison, 30 days of continuous
database activity at 0.25 CU would use 180 CU-hours. This is an estimate from
the published allowance, not a measurement of your project. Frequent feed
updates and executor polling can keep the database active, and larger compute
sizes use credits faster. Check actual usage in the Neon console. Moving MT5
to EC2 does not remove this database limit. A full month of uninterrupted
operation may require a different database hosting plan.

References:
- https://render.com/docs/free
- https://neon.com/blog/neon-free-plan-1-gb-per-project

## Launch one Windows instance after eligibility is confirmed

In EC2, choose Launch instance. Use these starting settings and confirm that
the selected image and instance type are eligible in your own account:

| Setting | Trial selection |
| --- | --- |
| Name | kmr-mt5-trial |
| Region | Mumbai, ap-south-1, if the eligible options are available |
| Image | Amazon-published Windows Server 2022 Full Base, x86_64 |
| Instance | t3.small if marked eligible; 2 GB is a starting trial size |
| Storage | Default Windows boot volume size, gp3; avoid adding data disks |
| Key pair | Create and privately keep a new RSA .pem key pair |
| RDP inbound | TCP 3389 from My IP only |
| Public address | Auto-assign for the trial |

Use the base Windows image without SQL Server or third-party Marketplace
software. Do not select an Arm/Graviton instance for this Windows MT5 setup.
Check the launch summary and credit coverage. If the console requires a paid
upgrade or the options are not eligible, stop before launching and reassess.
Windows compute, storage and public IPv4 usage draw from applicable credits.
No load balancer, NAT gateway or extra database is needed for this bridge.

Two GB is an initial test configuration, not a performance guarantee. Monitor
memory and CPU during the eight-timeframe initial history load. Restrict the
watchlist if needed before considering a larger, potentially ineligible instance.

## Connect to the cloud desktop

Wait for the instance to be running with status checks passing. Select it,
choose Connect, then RDP client. Choose Get password and decrypt the initial
Administrator password with your private .pem file in the AWS console.
Download the RDP connection file and open it on your PC.

Neither the .pem file nor the Administrator password goes into GitHub or KMR.
The RDP Administrator login, broker MT5 login and KMR feed login are separate.

AWS connection reference:
https://docs.aws.amazon.com/AWSEC2/latest/UserGuide/EC2_GetStarted.html

## Install MT5 and both workers inside the remote Windows desktop

All installation and commands below run ON THE EC2 DESKTOP, not on your home PC.
Install Git for Windows and Windows Python 3.11 x64 using their official
installers. Windows Server may not have winget preinstalled.

- Git: https://git-scm.com/install/windows
- Python: https://www.python.org/downloads/windows/
- Octa MT5 desktop: https://www.octabroker.com/downloads/mt5/

Open Octa MT5 inside the remote desktop and sign in to your existing demo
account. Confirm the account number and server against your Octa account
details. Keep the terminal open. Do not enter the broker password in Render.
To enable demo execution, check Allow algorithmic trading in Tools > Options >
Experts, uncheck Disable algorithmic trading via external Python API, and keep
the account-change protection checked. Ensure the Algo Trading toolbar button
is enabled. Keep the existing Render MT5_DEMO_ACCOUNT and MT5_DEMO_SERVER
binding set to this same demo account/server.

Open a new PowerShell window on the remote desktop:

```powershell
git --version
py -3.11 --version
New-Item -ItemType Directory -Force "$env:USERPROFILE\Projects"
Set-Location "$env:USERPROFILE\Projects"
git clone https://github.com/moohanakmp/kmr-market.git
Set-Location kmr-market
py -3.11 -m venv .venv-mt5
.\.venv-mt5\Scripts\python.exe -m pip install -r bridge/requirements.txt
```

Before starting the cloud feed, stop the local data bridge on your home PC
with Ctrl+C. Then run the following INSIDE the EC2 project folder:

```powershell
.\.venv-mt5\Scripts\python.exe bridge/mt5_bridge.py --url https://kmr-market.onrender.com --broker-utc-offset-hours 3
```

Use offset 3 for the Octa timestamp convention observed in the supplied logs.
The setting must match actual quote and candle timestamps; it is not an
automatic adjustment to make cached prices look current.

At the hidden prompt, enter the existing Render MT5_FEED_PASSWORD. The bridge
uses username mt5_feed. Its startup must show server-clock synchronization and
the configured UTC+3 conversion. If MT5 cannot be located, use --terminal with
the installed terminal64.exe path, as described in AUTO_SIGNALS.md.

If broker symbols differ, configure the supplied symbol-map JSON and add
--symbol-map bridge/symbol-map.json. Do not guess which contract to scan.

## Start the cloud demo executor

Stop the existing executor on your home PC with Ctrl+C before starting the
cloud executor. This normally releases the single-worker backend lease. After
an abnormal exit the cloud worker may need to wait up to five minutes for the
lease to expire. Do not run two executors for the same account.

Keep the remote data bridge running. Open a SECOND PowerShell window INSIDE
the cloud desktop. Enter the numeric demo account number at the prompt, not
the broker password or KMR feed password:

```powershell
Set-Location "$env:USERPROFILE\Projects\kmr-market"
$demoAccount = [long](Read-Host "Enter MT5 demo account number (digits only)")
.\.venv-mt5\Scripts\python.exe bridge/mt5_executor.py --url https://kmr-market.onrender.com --execute-demo --account $demoAccount --server OctaFX-Demo --broker-utc-offset-hours 3
```

Enter the existing Render MT5_FEED_PASSWORD only at the hidden KMR feed
password prompt. The startup should display DEMO execution enabled. Use the
same --terminal path and --symbol-map file for both processes if configured.
The cloud worker retains 0.01 lots, separate 5-pending and 5-open limits per
commodity, the eight enabled timeframes, TP2 and the existing TP1 SL rule.
Starting the worker does not prove an order was submitted: only eligible
signals are sent, and broker acceptance must be checked separately.
Manual plans created with Save plan remain planning records. This executor
consumes automatic signals only; moving it to the cloud does not enable
manual-plan submission to MT5.

## Validate before switching off your PC

Confirm both local workers have stopped. One active bridge and one executor
per account avoid competing feed sources and conflicting broker management.
Check the KMR web app: at least the currently trading instruments should show
accepted scanner activity and fresh streams when sufficient history is loaded.
Closed markets and missing 220-bar history may leave individual streams waiting.
Signals appear only when strategy conditions match.
Check the cloud executor output for preflight or API errors. When an eligible
signal is accepted, check MT5 Toolbox > Trade and KMR Trade plans for the
actual broker execution status, ticket and reported SL/TP.

Disconnect the RDP window while leaving the remote MT5 and PowerShell processes
open. Disconnecting RDP normally keeps the session running; signing out ends
the user processes. Check that the Windows session policy does not log off
disconnected sessions. Confirm fresh KMR activity after disconnecting before
turning off your own PC. The EC2 instance itself must remain running.

This first trial uses a logged-in remote Windows session. Automatic recovery
after Windows reboots or crashes is not configured by these commands. Reconnect,
log into MT5 and restart BOTH workers if the session ends. Dynamic TP1 SL
management stops when the executor stops, while broker-accepted SL, TP2 and
pending expiry remain. A supervised startup configuration is a separate next
step after the cloud feed and demo executor are verified.

## Finish the trial

Monitor credit balance and expiry in AWS Billing. Stopping the instance stops
MT5 and scans, but retained storage can still consume credits. When the trial
is finished, terminate the trial instance and inspect retained EBS volumes and
other trial resources before deleting only resources you no longer need.
Do not delete your Render service, Neon database or unrelated AWS resources.
