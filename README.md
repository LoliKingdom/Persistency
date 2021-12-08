#Persistency

There is no particular code that needs to be exposed through an API. (yet?)

Simply depend on this softly (no need to put into buildpath).

Persistency will set itself up after mods have been indexed and "activated" (set to active)

* All data related to Persistency will be exposed via {@link Launch#blackboard}.


* The main caches folder will be queried via {@link Launch#blackboard} with the key: "CachesFolderFile".
   - It is recommended that all caches use this folder, mod ids can be used as a nested directory to differentiate.


* The mods cache file will be queried via {@link Launch#blackboard} with the key: "ModsCacheFile".
   - It is recommended that you do not read/write from/to this File whatsoever. But it is here to show the filepath.
   - It could be that this file does not exist in the Filesystem when queried, meaning the file hadn't yet exist or needed to be refreshed.
   - This file should only be written onto disk when the client doesn't crash/exit during load.


* The temporary mods cache file will be queried via {@link Launch#blackboard} with the key: "TempModsCacheFile".
  - It is recommended that you do not read/write from/to this File whatsoever. But it is here to show the (temp) filepath.
  - It is here to ensure that ModsCacheFile isn't written directly onto disk when the client crashes/exits during load.
  - If this file exists, ModsCacheFile won't exist. (new in 1.1.0)
  - This file will be deletedOnExit.


* This shows whether or not the current load is consistent (structurally similar) with the last load, will be stored as a boolean via {@link Launch#blackboard} with the key: "ConsistentLoad".
  - Here is an easy boolean you could query to see if current load is near identical with the last one.
  - The comparisons are based on mod names + mod versions.
  - Configs are very hard to compare, hence it is not taken into account. Beware.