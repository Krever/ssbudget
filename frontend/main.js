// The entry of the page. 'scalajs:' is resolved by @scala-js/vite-plugin-scalajs: it asks sbt where the linked code of the frontend is, and
// takes the fast link (fastLinkJS) for the dev server and the optimized link (fullLinkJS) for a production build.
//
// Do not load the linked file by its path in index.html. That path holds the Scala version (target/scala-<version>/...): after a Scala
// update the page kept the output of the OLD compiler, which was still on the disk, and the build and the browser tests passed on it.
import 'scalajs:main.js'
