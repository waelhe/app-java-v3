'use client';

import React, { useState } from 'react';
import Link from 'next/link';
import {
  Phone, AlertTriangle, Ambulance, Flame, Shield, Car, Zap,
  Building, Stethoscope, Pill, Wrench, Droplets, Clock,
  ArrowRight, Heart, MapPin, ChevronDown
} from 'lucide-react';
import { useLanguage } from '@/contexts/LanguageContext';

// ═══════════════════════════════════════════════════════════════
// بيانات الطوارئ
// ═══════════════════════════════════════════════════════════════

const emergencyServices = [
  { id: '1', name: 'الطوارئ العامة', nameEn: 'General Emergency', number: '112', icon: AlertTriangle, color: 'red' },
  { id: '2', name: 'الإسعاف', nameEn: 'Ambulance', number: '110', icon: Ambulance, color: 'rose' },
  { id: '3', name: 'الإطفاء', nameEn: 'Fire Department', number: '113', icon: Flame, color: 'orange' },
  { id: '4', name: 'الشرطة', nameEn: 'Police', number: '122', icon: Shield, color: 'blue' },
  { id: '5', name: 'المرور', nameEn: 'Traffic', number: '115', icon: Car, color: 'indigo' },
  { id: '6', name: 'الكهرباء', nameEn: 'Electricity', number: '118', icon: Zap, color: 'yellow' },
];

const importantContacts = [
  { id: '1', name: 'مستشفى المواساة', nameEn: 'Al-Mowasat Hospital', number: '011-6621234', type: 'hospital' },
  { id: '2', name: 'مستشفى الشفاء', nameEn: 'Al-Shifa Hospital', number: '011-2234567', type: 'hospital' },
  { id: '3', name: 'بلدية قدسيا', nameEn: 'Qudsaya Municipality', number: '011-2345678', type: 'government' },
  { id: '4', name: 'مؤسسة المياه', nameEn: 'Water Authority', number: '011-2243567', type: 'utility' },
  { id: '5', name: 'شركة الاتصالات', nameEn: 'Telecom Company', number: '165', type: 'utility' },
  { id: '6', name: 'الدفاع المدني', nameEn: 'Civil Defense', number: '122', type: 'emergency' },
];

const onDutyPharmacies = [
  { id: '1', name: 'صيدلية الشفاء', nameEn: 'Al-Shifa Pharmacy', address: 'الشارع الرئيسي', addressEn: 'Main Street', phone: '011-2345678', hours: '24/7', isNight: true },
  { id: '2', name: 'صيدلية النور', nameEn: 'Al-Noor Pharmacy', address: 'الساحة', addressEn: 'Square', phone: '011-2345679', hours: '8 ص - 10 م', isNight: false },
  { id: '3', name: 'صيدلية الأمل', nameEn: 'Al-Amal Pharmacy', address: 'الحي الغربي', addressEn: 'West District', phone: '011-2345680', hours: '24/7', isNight: true },
  { id: '4', name: 'صيدلية الضاحية', nameEn: 'Dahia Pharmacy', address: 'الضاحية', addressEn: 'Dahia', phone: '011-2345681', hours: '8 ص - 10 م', isNight: false },
];

const onDutyDoctors = [
  { id: '1', name: 'د. أحمد محمد', nameEn: 'Dr. Ahmed Mohammad', specialty: 'طبيب عام', specialtyEn: 'General Practitioner', phone: '0999-123456', address: 'عيادة الساحة', addressEn: 'Square Clinic' },
  { id: '2', name: 'د. فاطمة علي', nameEn: 'Dr. Fatima Ali', specialty: 'طب أطفال', specialtyEn: 'Pediatrician', phone: '0999-234567', address: 'عيادة الأطفال', addressEn: 'Children Clinic' },
  { id: '3', name: 'د. محمود خالد', nameEn: 'Dr. Mahmoud Khaled', specialty: 'طبيب أسنان', specialtyEn: 'Dentist', phone: '0999-345678', address: 'عيادة الأسنان', addressEn: 'Dental Clinic' },
  { id: '4', name: 'د. سارة أحمد', nameEn: 'Dr. Sara Ahmed', specialty: 'طبيب نسائية', specialtyEn: 'Gynecologist', phone: '0999-456789', address: 'عيادة النساء', addressEn: "Women's Clinic" },
];

const homeEmergencies = [
  { id: '1', name: 'كهربائي مناوب', nameEn: 'On-Duty Electrician', phone: '0999-111111', specialty: 'تمديدات وإصلاح', specialtyEn: 'Wiring & Repair' },
  { id: '2', name: 'سباك مناوب', nameEn: 'On-Duty Plumber', phone: '0999-222222', specialty: 'تسربات وصيانة', specialtyEn: 'Leaks & Maintenance' },
  { id: '3', name: 'فني غاز', nameEn: 'Gas Technician', phone: '0999-333333', specialty: 'تمديدات وفحص', specialtyEn: 'Installation & Check' },
  { id: '4', name: 'فني تكييف', nameEn: 'AC Technician', phone: '0999-444444', specialty: 'صيانة وإصلاح', specialtyEn: 'Maintenance & Repair' },
  { id: '5', name: 'فتاح أقفال', nameEn: 'Locksmith', phone: '0999-555555', specialty: 'أقفال ومفاتيح', specialtyEn: 'Locks & Keys' },
  { id: '6', name: 'نقاش مناوب', nameEn: 'On-Duty Carpenter', phone: '0999-666666', specialty: 'أبواب ونوافذ', specialtyEn: 'Doors & Windows' },
];

const servicesStatus = [
  { id: '1', name: 'الكهرباء', nameEn: 'Electricity', status: 'متوفر', statusEn: 'Available', color: 'green' },
  { id: '2', name: 'المياه', nameEn: 'Water', status: 'متوفر', statusEn: 'Available', color: 'green' },
  { id: '3', name: 'الغاز', nameEn: 'Gas', status: 'انقطاع جزئي', statusEn: 'Partial Outage', color: 'yellow' },
  { id: '4', name: 'الإنترنت', nameEn: 'Internet', status: 'متوفر', statusEn: 'Available', color: 'green' },
];

// ═══════════════════════════════════════════════════════════════
// المكون الرئيسي
// ═══════════════════════════════════════════════════════════════

export default function EmergencyPage() {
  const { language } = useLanguage();
  const isArabic = language === 'ar';
  const [activeFilter, setActiveFilter] = useState('all');

  const sections = [
    { id: 'services', name: 'خدمات طوارئ', nameEn: 'Emergency Services' },
    { id: 'contacts', name: 'أرقام هامة', nameEn: 'Important Contacts' },
    { id: 'pharmacies', name: 'صيدليات مناوبة', nameEn: 'On-Duty Pharmacies' },
    { id: 'doctors', name: 'أطباء مناوبون', nameEn: 'On-Duty Doctors' },
    { id: 'home', name: 'طوارئ منزلية', nameEn: 'Home Emergencies' },
    { id: 'status', name: 'حالة الخدمات', nameEn: 'Services Status' },
  ];

  const scrollToSection = (id: string) => {
    document.getElementById(id)?.scrollIntoView({ behavior: 'smooth' });
  };

  return (
    <main className="min-h-screen bg-gradient-to-b from-red-50 to-white" dir={isArabic ? 'rtl' : 'ltr'}>
      {/* Header */}
      <header className="sticky top-0 z-50 bg-white/95 backdrop-blur-md border-b border-red-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-3">
          <div className="flex items-center justify-between">
            <div className="flex items-center gap-3">
              <Link href="/" className="p-2 hover:bg-gray-100 rounded-xl transition-colors">
                <ArrowRight className="w-5 h-5 text-gray-600" />
              </Link>
              <div className="p-2 bg-gradient-to-br from-red-500 to-rose-600 rounded-xl shadow-lg">
                <AlertTriangle className="w-6 h-6 text-white" />
              </div>
              <div>
                <h1 className="text-lg font-black text-gray-900">
                  {isArabic ? 'دليل الطوارئ' : 'Emergency Guide'}
                </h1>
                <p className="text-xs text-gray-500">
                  {isArabic ? 'خدمات متاحة على مدار الساعة' : 'Available 24/7 services'}
                </p>
              </div>
            </div>
            <span className="px-3 py-1 bg-red-100 text-red-700 text-xs font-bold rounded-full flex items-center gap-1">
              <span className="w-2 h-2 bg-red-500 rounded-full animate-pulse" />
              24/7
            </span>
          </div>
        </div>
      </header>

      {/* Quick Jump Navigation */}
      <div className="sticky top-[73px] z-40 bg-white/95 backdrop-blur-md border-b border-gray-100 shadow-sm">
        <div className="max-w-7xl mx-auto px-4 py-2">
          <div className="flex gap-2 overflow-x-auto pb-1 scrollbar-hide">
            {sections.map((section) => (
              <button
                key={section.id}
                onClick={() => scrollToSection(section.id)}
                className="px-4 py-2 bg-gray-100 hover:bg-red-100 text-gray-700 hover:text-red-700 text-xs font-bold rounded-xl whitespace-nowrap transition-colors"
              >
                {isArabic ? section.name : section.nameEn}
              </button>
            ))}
          </div>
        </div>
      </div>

      <div className="max-w-7xl mx-auto px-4 py-6 space-y-8">
        {/* ═════════════════════════════════════════════════════════ */}
        {/* خدمات الطوارئ */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="services" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-red-600 rounded-xl">
              <AlertTriangle className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '📞 خدمات الطوارئ' : '📞 Emergency Services'}
            </h2>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-3 md:grid-cols-6 gap-3">
            {emergencyServices.map((service) => {
              const Icon = service.icon;
              const colorClasses: Record<string, string> = {
                red: 'bg-red-50 border-red-200 text-red-600 hover:bg-red-100',
                rose: 'bg-rose-50 border-rose-200 text-rose-600 hover:bg-rose-100',
                orange: 'bg-orange-50 border-orange-200 text-orange-600 hover:bg-orange-100',
                blue: 'bg-blue-50 border-blue-200 text-blue-600 hover:bg-blue-100',
                indigo: 'bg-indigo-50 border-indigo-200 text-indigo-600 hover:bg-indigo-100',
                yellow: 'bg-yellow-50 border-yellow-200 text-yellow-600 hover:bg-yellow-100',
              };

              return (
                <a
                  key={service.id}
                  href={`tel:${service.number}`}
                  className={`group flex flex-col items-center p-4 rounded-2xl border-2 transition-all ${colorClasses[service.color]}`}
                >
                  <div className="p-3 bg-white rounded-xl shadow-sm mb-2 group-hover:scale-110 transition-transform">
                    <Icon className="w-6 h-6" />
                  </div>
                  <h3 className="text-sm font-bold mb-1">
                    {isArabic ? service.name : service.nameEn}
                  </h3>
                  <span className="text-lg font-black">{service.number}</span>
                  <Phone className="w-4 h-4 mt-2 animate-bounce" />
                </a>
              );
            })}
          </div>
        </section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* الأرقام الهامة */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="contacts" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-blue-600 rounded-xl">
              <Phone className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '📱 الأرقام الهامة' : '📱 Important Contacts'}
            </h2>
          </div>

          {/* فلتر */}
          <div className="flex gap-2 mb-4 overflow-x-auto pb-2">
            {[
              { id: 'all', name: 'الكل', nameEn: 'All' },
              { id: 'hospital', name: 'مستشفيات', nameEn: 'Hospitals' },
              { id: 'government', name: 'حكومي', nameEn: 'Government' },
              { id: 'utility', name: 'خدمات', nameEn: 'Utilities' },
              { id: 'emergency', name: 'طوارئ', nameEn: 'Emergency' },
            ].map((filter) => (
              <button
                key={filter.id}
                onClick={() => setActiveFilter(filter.id)}
                className={`px-4 py-2 rounded-xl text-sm font-bold transition-colors ${
                  activeFilter === filter.id
                    ? 'bg-blue-600 text-white'
                    : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                }`}
              >
                {isArabic ? filter.name : filter.nameEn}
              </button>
            ))}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
            {importantContacts
              .filter((contact) => activeFilter === 'all' || contact.type === activeFilter)
              .map((contact) => (
                <a
                  key={contact.id}
                  href={`tel:${contact.number}`}
                  className="flex items-center gap-3 p-4 bg-white rounded-xl border-2 border-gray-100 hover:border-blue-200 hover:shadow-md transition-all"
                >
                  <div className="p-3 bg-blue-100 rounded-xl">
                    <Building className="w-5 h-5 text-blue-600" />
                  </div>
                  <div className="flex-1">
                    <h3 className="font-bold text-gray-900">
                      {isArabic ? contact.name : contact.nameEn}
                    </h3>
                    <span className="text-sm text-gray-500">{contact.number}</span>
                  </div>
                  <Phone className="w-5 h-5 text-blue-600" />
                </a>
              ))}
          </div>
        </section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* الصيدليات المناوبة */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="pharmacies" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-emerald-600 rounded-xl">
              <Pill className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '💊 الصيدليات المناوبة' : '💊 On-Duty Pharmacies'}
            </h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {onDutyPharmacies.map((pharmacy) => (
              <a
                key={pharmacy.id}
                href={`tel:${pharmacy.phone}`}
                className="flex items-start gap-3 p-4 bg-white rounded-xl border-2 border-gray-100 hover:border-emerald-200 hover:shadow-md transition-all"
              >
                <div className="p-3 bg-emerald-100 rounded-xl">
                  <Pill className="w-5 h-5 text-emerald-600" />
                </div>
                <div className="flex-1">
                  <div className="flex items-center gap-2 mb-1">
                    <h3 className="font-bold text-gray-900">
                      {isArabic ? pharmacy.name : pharmacy.nameEn}
                    </h3>
                    {pharmacy.isNight && (
                      <span className="px-2 py-0.5 bg-violet-100 text-violet-700 text-[10px] font-bold rounded-full">
                        {isArabic ? 'مناوبة ليلية' : 'Night Shift'}
                      </span>
                    )}
                  </div>
                  <div className="flex items-center gap-2 text-sm text-gray-500 mb-1">
                    <MapPin className="w-4 h-4" />
                    <span>{isArabic ? pharmacy.address : pharmacy.addressEn}</span>
                  </div>
                  <div className="flex items-center gap-2 text-sm text-gray-500">
                    <Clock className="w-4 h-4" />
                    <span>{pharmacy.hours}</span>
                  </div>
                </div>
                <div className="p-3 bg-emerald-600 rounded-xl">
                  <Phone className="w-5 h-5 text-white" />
                </div>
              </a>
            ))}
          </div>
        </section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* الأطباء المناوبون */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="doctors" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-rose-600 rounded-xl">
              <Stethoscope className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '👨‍⚕️ الأطباء المناوبون' : '👨‍⚕️ On-Duty Doctors'}
            </h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
            {onDutyDoctors.map((doctor) => (
              <a
                key={doctor.id}
                href={`tel:${doctor.phone}`}
                className="flex items-start gap-3 p-4 bg-white rounded-xl border-2 border-gray-100 hover:border-rose-200 hover:shadow-md transition-all"
              >
                <div className="p-3 bg-rose-100 rounded-xl">
                  <Stethoscope className="w-5 h-5 text-rose-600" />
                </div>
                <div className="flex-1">
                  <h3 className="font-bold text-gray-900 mb-1">
                    {isArabic ? doctor.name : doctor.nameEn}
                  </h3>
                  <p className="text-sm text-rose-600 font-medium mb-1">
                    {isArabic ? doctor.specialty : doctor.specialtyEn}
                  </p>
                  <div className="flex items-center gap-2 text-sm text-gray-500">
                    <MapPin className="w-4 h-4" />
                    <span>{isArabic ? doctor.address : doctor.addressEn}</span>
                  </div>
                </div>
                <div className="p-3 bg-rose-600 rounded-xl">
                  <Phone className="w-5 h-5 text-white" />
                </div>
              </a>
            ))}
          </div>
        </section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* طوارئ منزلية */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="home" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-amber-600 rounded-xl">
              <Wrench className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '🔧 طوارئ منزلية' : '🔧 Home Emergencies'}
            </h2>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-3">
            {homeEmergencies.map((service) => (
              <a
                key={service.id}
                href={`tel:${service.phone}`}
                className="flex items-center gap-3 p-4 bg-white rounded-xl border-2 border-gray-100 hover:border-amber-200 hover:shadow-md transition-all"
              >
                <div className="p-3 bg-amber-100 rounded-xl">
                  <Wrench className="w-5 h-5 text-amber-600" />
                </div>
                <div className="flex-1">
                  <h3 className="font-bold text-gray-900">
                    {isArabic ? service.name : service.nameEn}
                  </h3>
                  <p className="text-sm text-gray-500">
                    {isArabic ? service.specialty : service.specialtyEn}
                  </p>
                </div>
                <Phone className="w-5 h-5 text-amber-600" />
              </a>
            ))}
          </div>
        </section>

        {/* ═════════════════════════════════════════════════════════ */}
        {/* حالة الخدمات */}
        {/* ═════════════════════════════════════════════════════════ */}
        <section id="status" className="scroll-mt-32">
          <div className="flex items-center gap-3 mb-4">
            <div className="p-2 bg-teal-600 rounded-xl">
              <Zap className="w-5 h-5 text-white" />
            </div>
            <h2 className="text-xl font-black text-gray-900">
              {isArabic ? '⚡ حالة الخدمات' : '⚡ Services Status'}
            </h2>
          </div>

          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {servicesStatus.map((service) => {
              const colorClasses: Record<string, string> = {
                green: 'bg-green-100 border-green-200 text-green-700',
                yellow: 'bg-yellow-100 border-yellow-200 text-yellow-700',
                red: 'bg-red-100 border-red-200 text-red-700',
              };

              return (
                <div
                  key={service.id}
                  className={`flex flex-col items-center p-4 rounded-xl border-2 ${colorClasses[service.color]}`}
                >
                  <div className="flex items-center gap-2 mb-2">
                    {service.color === 'green' ? (
                      <span className="w-3 h-3 bg-green-500 rounded-full animate-pulse" />
                    ) : (
                      <span className="w-3 h-3 bg-yellow-500 rounded-full animate-pulse" />
                    )}
                    <span className="font-bold">
                      {isArabic ? service.name : service.nameEn}
                    </span>
                  </div>
                  <span className="text-sm font-medium">
                    {isArabic ? service.status : service.statusEn}
                  </span>
                </div>
              );
            })}
          </div>
        </section>
      </div>

      {/* Footer CTA */}
      <div className="sticky bottom-0 bg-gradient-to-r from-red-600 to-rose-600 p-4 shadow-lg">
        <div className="max-w-7xl mx-auto flex items-center justify-between">
          <div className="flex items-center gap-3">
            <Heart className="w-5 h-5 text-white animate-pulse" />
            <span className="text-white font-bold">
              {isArabic ? 'هل تحتاج مساعدة عاجلة؟' : 'Need urgent help?'}
            </span>
          </div>
          <a
            href="tel:112"
            className="flex items-center gap-2 px-6 py-3 bg-white text-red-600 rounded-xl font-bold hover:bg-gray-100 transition-colors"
          >
            <Phone className="w-5 h-5" />
            {isArabic ? 'اتصل بالطوارئ' : 'Call Emergency'}
          </a>
        </div>
      </div>
    </main>
  );
}
