# Third-party notices

tuifileman bundles or links the following libraries. Their licences apply to them, not to tuifileman.

## Eclipse JGit (ignore matching) - BSD-3-Clause (Eclipse Distribution License 1.0)

Used for `.gitignore` matching (`org.eclipse.jgit.ignore.*`, `org.eclipse.jgit:org.eclipse.jgit`).
Source: https://github.com/eclipse-jgit/jgit

```
Copyright (c) 2007, Eclipse Foundation, Inc. and its licensors. All rights reserved.
(Individual JGit source files carry their own contributor copyright headers.)

Eclipse Distribution License - v 1.0

Redistribution and use in source and binary forms, with or without modification,
are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this
  list of conditions and the following disclaimer.

- Redistributions in binary form must reproduce the above copyright notice, this
  list of conditions and the following disclaimer in the documentation and/or
  other materials provided with the distribution.

- Neither the name of the Eclipse Foundation, Inc. nor the names of its
  contributors may be used to endorse or promote products derived from this
  software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED.
IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## SLF4J API - MIT

`org.slf4j:slf4j-api` (https://www.slf4j.org), used by JGit.

```
Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland)
All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining
a copy of this software and associated documentation files (the
"Software"), to deal in the Software without restriction, including
without limitation the rights to use, copy, modify, merge, publish,
distribute, sublicense, and/or sell copies of the Software, and to
permit persons to whom the Software is furnished to do so, subject to
the following conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION
WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
```

## Lanterna - LGPL-3.0-or-later

`com.googlecode.lanterna:lanterna` 3.2.0-alpha1 (https://github.com/mabe02/lanterna), terminal user
interface. Copyright (C) 2010-2020 Martin Berglund and contributors. Licensed under the GNU Lesser General
Public License, version 3 or (at your option) any later version. The full texts are in
`licenses/LGPL-3.0.txt` and `licenses/GPL-3.0.txt` (the LGPL incorporates the GPL by reference).

Lanterna is compiled into the jar and into the native executable, and is not modified by tuifileman. As
LGPL section 4 requires, you may replace it with a modified version and relink: tuifileman is free software
under the MIT licence, which puts no restriction on modifying it or on reverse engineering it to debug such
modifications, and the complete source, `pom.xml` and the build scripts are published at the project
repository. To rebuild with your own Lanterna, change the Lanterna version (or add your jar) in `pom.xml` and
run the steps in the README section "Build (Maven / NetBeans)".

## JNA - LGPL-2.1-or-later OR Apache-2.0

`net.java.dev.jna:jna` and `jna-platform` 5.17.0 (https://github.com/java-native-access/jna), used for
Windows console access. JNA is dual-licensed; tuifileman uses it under the Apache-2.0 option. The full text
is in `licenses/Apache-2.0.txt`. Copyright (c) the JNA contributors; the jar includes the unmodified
`jnidispatch` native libraries.

## Build tooling (not distributed with the source)

The release executable is built with GraalVM Community Edition Native Image, which adds the Substrate VM
runtime and parts of the OpenJDK class library (GPL-2.0 with the Classpath Exception) to the executable.
