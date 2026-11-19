const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_org_123";
const BOB_UID = "bob_org_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function validEmployeePayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    employeeCode: "HGC-101",
    fullName: "Aarav Patel",
    username: "aarav.patel",
    passwordDigest: "pbkdf2_sha256$65536$0011223344556677$abcdef0123456789abcdef0123456789",
    role: "EMPLOYEE",
    department: "Kiln Operations",
    designation: "Shift Operator",
    shift: "MORNING_A",
    active: true,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validAttendancePayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    employeeDocId: "emp_1",
    employeeCode: "HGC-101",
    employeeName: "Aarav Patel",
    department: "Kiln Operations",
    shift: "MORNING_A",
    dateKey: "2026-11-19",
    monthKey: "2026-11",
    status: "PRESENT",
    checkInTimeMs: Date.now() - 3600000,
    checkOutTimeMs: 0,
    workedMinutes: 0,
    scanMethod: "QR",
    note: "",
    correctedByHod: false,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

function validOvertimePayload(uid, overrides = {}) {
  const now = new Date(Date.now() - 2000);
  return {
    userId: uid,
    employeeDocId: "emp_1",
    employeeCode: "HGC-101",
    employeeName: "Aarav Patel",
    department: "Kiln Operations",
    dateKey: "2026-11-19",
    monthKey: "2026-11",
    hours: 2.5,
    reason: "Kiln heat recovery maintenance",
    approved: true,
    editedByHod: false,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

test("Unauthenticated access is denied across all collections", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/employees`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/attendance`).get());
  await assertFails(unauthDb.collection(`users/${ALICE_UID}/overtime`).get());
  await assertFails(
    unauthDb
      .collection(`users/${ALICE_UID}/employees`)
      .doc("emp_1")
      .set(validEmployeePayload(ALICE_UID))
  );
});

test("Cross-user isolation prevents Bob from reading or writing Alice workspace", async () => {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.doc(`users/${ALICE_UID}/employees/emp_1`).set(validEmployeePayload(ALICE_UID));
    await db
      .doc(`users/${ALICE_UID}/attendance/emp_1_2026-11-19`)
      .set(validAttendancePayload(ALICE_UID));
    await db.doc(`users/${ALICE_UID}/overtime/ot_1`).set(validOvertimePayload(ALICE_UID));
  });

  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  await assertFails(bobDb.collection(`users/${ALICE_UID}/employees`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/employees/emp_1`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/attendance/emp_1_2026-11-19`).get());
  await assertFails(bobDb.doc(`users/${ALICE_UID}/overtime/ot_1`).get());
  await assertFails(
    bobDb.doc(`users/${ALICE_UID}/employees/emp_2`).set(validEmployeePayload(BOB_UID))
  );
});

test("Owner can create, list, update, and deactivate employees with strict schema", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const empRef = aliceDb.doc(`users/${ALICE_UID}/employees/emp_1`);

  await assertSucceeds(empRef.set(validEmployeePayload(ALICE_UID)));
  await assertSucceeds(aliceDb.collection(`users/${ALICE_UID}/employees`).get());

  await assertSucceeds(
    empRef.update({
      active: false,
      designation: "Senior Operator",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject ghost field & @DocumentId 'id' field
  await assertFails(
    aliceDb
      .doc(`users/${ALICE_UID}/employees/emp_2`)
      .set(validEmployeePayload(ALICE_UID, { id: "emp_2" }))
  );
  await assertFails(
    empRef.update({
      isAdminOverride: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );
  // Reject immutable createdAt modification
  await assertFails(
    empRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );
  // Reject future timestamp
  await assertFails(
    aliceDb
      .doc(`users/${ALICE_UID}/employees/emp_3`)
      .set(validEmployeePayload(ALICE_UID, { createdAt: new Date(Date.now() + 60000) }))
  );
});

test("Owner can record attendance check-in, check-out, HOD correction, and 3-month cleanup", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const attRef = aliceDb.doc(`users/${ALICE_UID}/attendance/emp_1_2026-11-19`);

  await assertSucceeds(attRef.set(validAttendancePayload(ALICE_UID)));

  // Employee check-out update
  await assertSucceeds(
    attRef.update({
      checkOutTimeMs: Date.now() - 1000,
      workedMinutes: 480,
      status: "PRESENT",
      scanMethod: "QR",
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // HOD correction update
  await assertSucceeds(
    attRef.update({
      status: "HALF_DAY",
      checkOutTimeMs: Date.now() - 500,
      workedMinutes: 240,
      scanMethod: "HOD_CORRECTED",
      note: "Forgotten check-out updated by HOD",
      correctedByHod: true,
      updatedAt: new Date(Date.now() - 200),
    })
  );

  // Reject immutable employeeDocId mutation
  await assertFails(
    attRef.update({
      employeeDocId: "emp_999",
      updatedAt: new Date(Date.now() - 100),
    })
  );

  // Allow deletion for 3-month retention pruning
  await assertSucceeds(attRef.delete());
});

test("Owner can record, update, and prune overtime records with validation bounds", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const otRef = aliceDb.doc(`users/${ALICE_UID}/overtime/ot_1`);

  await assertSucceeds(otRef.set(validOvertimePayload(ALICE_UID)));

  await assertSucceeds(
    otRef.update({
      hours: 3.5,
      reason: "Extended boiler shift",
      editedByHod: true,
      approved: true,
      updatedAt: new Date(Date.now() - 500),
    })
  );

  // Reject out-of-bounds OT hours (> 16)
  await assertFails(
    aliceDb
      .doc(`users/${ALICE_UID}/overtime/ot_2`)
      .set(validOvertimePayload(ALICE_UID, { hours: 24 }))
  );

  await assertSucceeds(otRef.delete());
});
