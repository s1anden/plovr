{
  // "This config file is used to test org.plovr.Main."
  "id": "integration-test",
  "paths": ".",
  "inputs": "main.js",
  
  // This must be specified because datetimesymbols.js from the Closure Library
  // will be included, so when test-raw.html loads each input in RAW mode,
  // it is important that the proper charset be used.
  "output-charset": "UTF-8"
}
