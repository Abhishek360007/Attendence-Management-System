// Add / edit employee and create-login dialogs, shared by the list and profile pages.
import { api, formModal, hasRole, toast } from "../core.js";
import { invalidate, lookup } from "../ui.js";

export async function employeeDialog(existing, prefill = {}, onSaved) {
  const [departments, schedules, employees] = await Promise.all([
    lookup("departments"), lookup("schedules"), lookup("employees")
  ]);
  const e = existing || prefill;
  formModal({
    title: existing ? `Edit ${existing.fullName}` : "Add employee",
    fields: [
      { name: "fullName", label: "Full name", required: true },
      { name: "employeeCode", label: "Employee ID", required: true, help: "Your own staff number, e.g. E042." },
      { name: "deviceUserId", label: "Device user ID", help: "The user number on the ZKTeco device. Links punches to this employee." },
      { name: "designation", label: "Designation" },
      { name: "departmentId", label: "Department", type: "select", placeholder: "None",
        options: departments.map((d) => ({ value: d.id, label: d.name })) },
      { name: "managerId", label: "Reports to", type: "select", placeholder: "Department head",
        options: employees.filter((x) => !existing || x.id !== existing.id).map((x) => ({ value: x.id, label: `${x.fullName} (${x.employeeCode})` })) },
      { name: "workScheduleId", label: "Work schedule", type: "select", placeholder: "Department's schedule",
        options: schedules.map((s) => ({ value: s.id, label: `${s.name} (${s.startTime.slice(0, 5)}–${s.endTime.slice(0, 5)})` })) },
      { name: "email", label: "Email", type: "email" },
      { name: "phone", label: "Phone" },
      { name: "monthlySalary", label: "Monthly salary", type: "number", min: "0", step: "0.01", help: "Used for payroll. Only HR and admins can see it." },
      { name: "joinedOn", label: "Joined on", type: "date" },
      { name: "leftOn", label: "Left on", type: "date", help: "Attendance is not expected after this date." },
      { name: "active", label: "Active", type: "checkbox", wide: true }
    ],
    values: { active: true, ...e },
    submitLabel: existing ? "Save" : "Add employee",
    onSubmit: async (v) => {
      for (const k of ["departmentId", "managerId", "workScheduleId"]) v[k] = v[k] ? Number(v[k]) : null;
      const saved = existing ? await api.put(`/api/employees/${existing.id}`, v) : await api.post("/api/employees", v);
      invalidate("employees", "departments");
      toast(existing ? "Employee updated." : "Employee added.");
      onSaved?.(saved);
    }
  });
}

export function loginDialog(emp, onSaved) {
  const roles = [{ value: "EMPLOYEE", label: "Employee" }, { value: "MANAGER", label: "Manager" }];
  if (hasRole("ADMIN")) roles.push({ value: "HR", label: "HR" }, { value: "ADMIN", label: "Admin" });
  formModal({
    title: `Create sign-in for ${emp.fullName}`,
    intro: "They can then see their attendance, apply for leave and request punch corrections. Share the temporary password with them; they can change it under My account.",
    fields: [
      { name: "email", label: "Email", type: "email", required: true },
      { name: "password", label: "Temporary password", type: "password", required: true, help: "At least 8 characters." },
      { name: "role", label: "Role", type: "select", options: roles, required: true }
    ],
    values: { email: emp.email || "", role: "EMPLOYEE" },
    submitLabel: "Create sign-in",
    onSubmit: async (v) => {
      const saved = await api.post(`/api/employees/${emp.id}/login`, v);
      toast("Sign-in created.");
      onSaved?.(saved);
    }
  });
}
