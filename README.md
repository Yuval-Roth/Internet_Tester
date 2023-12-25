An application that monitors and logs internet / ethernet connection to a specified ip(s) using pings

-------------

**Instructions:**



In order to run this application, run the Internet-Tester.exe file from command line (or a .bat file)
and in the argument insert the target ip(s)

Examples:

start Internet-Tester.exe 8.8.8.8 8.8.4.4 1.0.0.1  (*read note below)

start Internet-Tester.exe 8.8.8.8

start Internet-Tester.exe 192.168.1.1

*Note: putting multiple ips in the argument adds redundancy. 
This means that connection is lost if and only if all connections to all ips are lost
This is best used when checking for internet access in general.
If you want to check a connection to multiple ips without redundancy, start the program once for each ip


 ***** Logs and the config file are located in the 'app' folder *****
